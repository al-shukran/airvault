package app.lensly.core.wallet

import app.airvault.core.model.Curve
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.lensly.core.algorand.LedgerEd25519
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.btc.rootFromSeed
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.PathStep
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.byteVector64

/**
 * The key access of one Mnemonic wallet (spec 3.2). Each call derives the key it needs from [seed] and zeroes it when the call returns,
 * except for the Bitcoin keys, whose bitcoin-kmp objects cannot be wiped, as with [app.lensly.core.btc.BtcAccount]. The caller owns [seed]
 * and zeroes it.
 *
 * Three kinds of key are held, and nothing else:
 * - the Algorand account key at `m/44'/283'/account'/0/0` on ED25519_BIP32, which [LedgerEd25519] derives;
 * - the Solana account key at `m/44'/501'/account'` on ED25519_SLIP10, the SLIP-0010 key of [SolanaAccount];
 * - the Bitcoin account keys at `m/purpose'/coin'/account'/branch/index` on SECP256K1, for the purposes and coin types of [ScriptType] and
 *   [BtcNetwork], with branch 0 or 1 and a non-hardened index. These are the keys [app.lensly.core.btc.BtcAccount] derives.
 *
 * The raw private key is never handed out: [withPrivateKey] is unsupported.
 */
class WalletKeyAccess(private val seed: ByteArray) : KeyAccess {
    override fun publicKey(curve: Curve, path: Path): PublicKey = when (curve) {
        Curve.SECP256K1 -> PublicKey(curve, withBtcKey(path) { key -> key.publicKey().value.toByteArray() })
        Curve.ED25519_SLIP10 -> PublicKey(curve, SolanaAccount.publicKey(seed, solanaAccountOf(path)))
        else -> withAccount(curve, path) { key -> PublicKey(curve, key.publicKey.copyOf()) }
    }

    /**
     * The ECDSA signature of [digest32] with the Bitcoin key at [path]: low-S, as bitcoin-kmp's Crypto.sign gives it, with the recovery id
     * of the key's public key.
     */
    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature {
        require(digest32.size == 32) { "A digest is 32 bytes." }
        return withBtcKey(path) { key ->
            val signature = Crypto.sign(digest32, key).toByteArray()
            val publicKey = key.publicKey().value.toByteArray()
            val recoveryId = (0..1).firstOrNull { id ->
                Crypto.recoverPublicKey(signature.byteVector64(), digest32, id).value.toByteArray().contentEquals(publicKey)
            } ?: error("No recovery id recovers the key.")
            EcdsaSignature(signature.copyOfRange(0, 32), signature.copyOfRange(32, 64), recoveryId)
        }
    }

    /**
     * The BIP340 signature of [digest32] with the Bitcoin key at [path], tweaked for a taproot output. [tweak] is the merkle root of the
     * output's script tree, or null for a key-path output with no script tree (BIP86). The aux is null, as bitcoin-kmp's Psbt.sign uses it,
     * so the signature is deterministic.
     */
    override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray {
        require(digest32.size == 32) { "A digest is 32 bytes." }
        require(tweak == null || tweak.size == 32) { "A taproot merkle root is 32 bytes." }
        val taprootTweak = tweak?.let { Crypto.TaprootTweak.ScriptPathTweak(it.byteVector32()) } ?: Crypto.TaprootTweak.KeyPathTweak
        return withBtcKey(path) { key -> Crypto.signSchnorr(digest32.byteVector32(), key, taprootTweak, null).toByteArray() }
    }

    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray = when (curve) {
        Curve.ED25519_SLIP10 -> signSolana(path, message)
        else -> withAccount(curve, path) { key -> Ed25519Signer.sign(key.kL, message) }
    }

    override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T =
        throw UnsupportedOperationException("This wallet's key access does not hand out raw private keys.")

    private fun <T> withAccount(curve: Curve, path: Path, use: (LedgerEd25519.Key) -> T): T {
        val key = LedgerEd25519.algorandAccount(seed, algorandAccountOf(curve, path))
        try {
            return use(key)
        } finally {
            key.wipe()
        }
    }

    /** Runs [use] with the Bitcoin key at [path], derived from the seed. A path that is not a Bitcoin account key is refused first. */
    private fun <T> withBtcKey(path: Path, use: (PrivateKey) -> T): T {
        val key = rootFromSeed(seed).derivePrivateKey(btcKeyPathOf(path)).privateKey
        return use(key)
    }

    /** Signs [message] with the SLIP-0010 key of the Solana account at [path], and zeroes the key before it returns. */
    private fun signSolana(path: Path, message: ByteArray): ByteArray =
        SolanaAccount.node(seed, solanaAccountOf(path)).use { node ->
            val key = node.privateKey()
            try {
                Ed25519Signer.sign(key, message)
            } finally {
                key.fill(0)
            }
        }

    companion object {
        /** The derivation path of Algorand account [account]: `m/44'/283'/account'/0/0`, the path the Ledger Algorand app derives. */
        fun algorandPath(account: Int): Path = Path(
            listOf(
                PathStep(44, hardened = true),
                PathStep(ALGORAND_COIN_TYPE, hardened = true),
                PathStep(account, hardened = true),
                PathStep(0, hardened = false),
                PathStep(0, hardened = false),
            ),
        )

        private const val ALGORAND_COIN_TYPE = 283
    }
}

/** The purposes of the Bitcoin account paths: the BIP43 purpose of each [ScriptType]. */
private val BITCOIN_PURPOSES: Set<Int> = ScriptType.entries.map { it.purpose }.toSet()

/** The coin types of the Bitcoin account paths: the coin type of each [BtcNetwork]. */
private val BITCOIN_COIN_TYPES: Set<Int> = BtcNetwork.entries.map { it.coinType }.toSet()

/**
 * The BIP32 path of the Bitcoin account key at [path], m/purpose'/coin'/account'/branch/index, with the purpose and the coin type of a
 * [ScriptType] and [BtcNetwork], a branch of 0 or 1, and non-hardened branch and index. Any other path is refused.
 */
private fun btcKeyPathOf(path: Path): KeyPath {
    val steps = path.steps
    val isBitcoinAccountKey = steps.size == 5 &&
        steps[0].hardened && steps[0].index in BITCOIN_PURPOSES &&
        steps[1].hardened && steps[1].index in BITCOIN_COIN_TYPES &&
        steps[2].hardened && steps[2].index >= 0 &&
        !steps[3].hardened && steps[3].index in 0..1 &&
        !steps[4].hardened && steps[4].index >= 0
    require(isBitcoinAccountKey) { "$path is not a Bitcoin account key path." }
    return KeyPath(steps.map { step -> if (step.hardened) DeterministicWallet.hardened(step.index.toLong()) else step.index.toLong() })
}

/** The account index of a Solana path on ED25519_SLIP10, m/44'/501'/account'. Any other path is refused. */
private fun solanaAccountOf(path: Path): Int {
    val steps = path.steps
    val isSolana = steps.size == 3 &&
        steps[0] == PathStep(44, hardened = true) &&
        steps[1] == PathStep(501, hardened = true) &&
        steps[2].hardened
    require(isSolana) { "$path is not a Solana account path." }
    return steps[2].index
}

/** The account index of an Algorand path on ED25519_BIP32. Any other curve or path is refused. */
private fun algorandAccountOf(curve: Curve, path: Path): Int {
    require(curve == Curve.ED25519_BIP32) { "Only Ed25519 BIP32 keys are held here." }
    val steps = path.steps
    val isAlgorand = steps.size == 5 &&
        steps[0] == PathStep(44, hardened = true) &&
        steps[1] == PathStep(283, hardened = true) &&
        steps[2].hardened &&
        steps[3] == PathStep(0, hardened = false) &&
        steps[4] == PathStep(0, hardened = false)
    require(isAlgorand) { "$path is not an Algorand account path." }
    return steps[2].index
}
