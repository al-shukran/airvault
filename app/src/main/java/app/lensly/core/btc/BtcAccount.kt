package app.lensly.core.btc

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.byteVector32
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.utils.Numeric

/**
 * One Bitcoin Account: the key at m/purpose'/coin'/[accountIndex]' of a [ScriptType] on a [BtcNetwork]. Its addresses are
 * the children m/.../0/i (receive) and m/.../1/i (change).
 *
 * The BIP32 root comes from the BIP39 seed through core/wallet's root derivation, the one HdWallet uses, so an Account's
 * [masterFingerprint] equals the fingerprint of the same seed's Ethereum keys. The key objects held here cannot be wiped:
 * the caller zeroes the seed it passed in.
 */
class BtcAccount private constructor(
    val network: BtcNetwork,
    val scriptType: ScriptType,
    /** The derivation index of this Account inside its Wallet, unique for its [ScriptType]. */
    val accountIndex: Int,
    /** The first 4 bytes of HASH160 of the master public key, as an unsigned 32-bit value. */
    val masterFingerprint: Long,
    private val accountKey: DeterministicWallet.ExtendedPrivateKey,
) {
    /** The account path m/purpose'/coin'/[accountIndex]'. */
    val path: KeyPath = scriptType.accountPath(network, accountIndex)

    private val publicAccountKey: DeterministicWallet.ExtendedPublicKey = accountKey.extendedPublicKey

    /** The account's extended public key, which watches every address of the Account without the seed. */
    val xpub: DeterministicWallet.ExtendedPublicKey
        get() = publicAccountKey

    /** The receive address at m/.../0/[index]. */
    fun receiveAddress(index: Int): String = address(RECEIVE, index)

    /** The change address at m/.../1/[index]. */
    fun changeAddress(index: Int): String = address(CHANGE, index)

    /** The watch-only half of this Account. */
    fun watchOnly(): BtcWatchOnlyAccount =
        BtcWatchOnlyAccount(network, scriptType, masterFingerprint, path, publicAccountKey)

    /** The scriptPubKey of the address at [branch]/[index]. */
    internal fun scriptPubKey(branch: Int, index: Int): ByteArray {
        val key = publicKey(branch, index)
        val script = when (scriptType) {
            ScriptType.LEGACY -> Script.pay2pkh(key)
            ScriptType.NESTED_SEGWIT -> Script.pay2sh(Script.pay2wpkh(key))
            ScriptType.NATIVE_SEGWIT -> Script.pay2wpkh(key)
            ScriptType.TAPROOT -> Script.pay2tr(key.xOnly(), Crypto.TaprootTweak.KeyPathTweak)
        }
        return Script.write(script)
    }

    /** The private key of the address at [branch]/[index]. The caller must not keep it. */
    internal fun privateKey(branch: Int, index: Int): PrivateKey =
        accountKey.derivePrivateKey(checkBranch(branch).toLong()).derivePrivateKey(checkIndex(index).toLong()).privateKey

    /** The public key of the address at [branch]/[index]. */
    internal fun publicKey(branch: Int, index: Int): PublicKey =
        publicAccountKey.derivePublicKey(checkBranch(branch).toLong()).derivePublicKey(checkIndex(index).toLong()).publicKey

    private fun address(branch: Int, index: Int): String {
        val key = publicKey(branch, index)
        val chainHash = network.chain.chainHash
        return when (scriptType) {
            ScriptType.LEGACY -> Bitcoin.computeP2PkhAddress(key, chainHash)
            ScriptType.NESTED_SEGWIT -> Bitcoin.computeP2ShOfP2WpkhAddress(key, chainHash)
            ScriptType.NATIVE_SEGWIT -> Bitcoin.computeP2WpkhAddress(key, chainHash)
            ScriptType.TAPROOT -> Bitcoin.computeBIP86Address(key, chainHash)
        }
    }

    companion object {
        /** The standard gap limit: the run of empty addresses a restore scan accepts before it stops. */
        const val GAP_LIMIT = 20

        /** Branch 0: receive addresses. */
        internal const val RECEIVE = 0

        /** Branch 1: change addresses. */
        internal const val CHANGE = 1

        /** The Account at [accountIndex] of [scriptType] on [network], derived from a BIP39 [seed] of 16 to 64 bytes. */
        fun fromSeed(seed: ByteArray, network: BtcNetwork, scriptType: ScriptType, accountIndex: Int = 0): BtcAccount {
            val root = rootFromSeed(seed)
            val key = root.derivePrivateKey(scriptType.accountPath(network, accountIndex))
            return BtcAccount(network, scriptType, accountIndex, root.fingerprint(), key)
        }
    }
}

private const val MIN_SEED_BYTES = 16
private const val MAX_SEED_BYTES = 64
private const val PRIVATE_KEY_BYTES = 32

/**
 * The BIP32 master key of [seed]. It is derived with the same call core/wallet's HdWallet uses for its root (web3j's
 * Bip32ECKeyPair), so the two wallets agree on the root for every seed. The root is then carried as bitcoin-kmp's key type.
 */
internal fun rootFromSeed(seed: ByteArray): DeterministicWallet.ExtendedPrivateKey {
    require(seed.size in MIN_SEED_BYTES..MAX_SEED_BYTES) { "BIP32 seed must be 16 to 64 bytes" }
    val master = Bip32ECKeyPair.generateKeyPair(seed)
    return DeterministicWallet.ExtendedPrivateKey(
        secretkeybytes = Numeric.toBytesPadded(master.privateKey, PRIVATE_KEY_BYTES).byteVector32(),
        chaincode = master.chainCode.byteVector32(),
        depth = 0,
        path = KeyPath.empty,
        parent = 0L,
    )
}

private fun checkBranch(branch: Int): Int {
    require(branch == BtcAccount.RECEIVE || branch == BtcAccount.CHANGE) { "branch must be 0 (receive) or 1 (change)" }
    return branch
}

private fun checkIndex(index: Int): Int {
    require(index >= 0) { "address index must be 0 or more" }
    return index
}
