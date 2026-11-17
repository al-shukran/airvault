package app.lensly.core.btc

import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath

/**
 * The watch-only half of a Bitcoin Account: the account xpub, the master fingerprint and the path it sits on. An online
 * wallet holding this can watch every address of the Account and build requests, but it cannot sign.
 */
data class BtcWatchOnlyAccount(
    val network: BtcNetwork,
    val scriptType: ScriptType,
    val masterFingerprint: Long,
    val path: KeyPath,
    val xpub: DeterministicWallet.ExtendedPublicKey,
) {
    /**
     * The output descriptor, with its BIP380 checksum. One descriptor covers both branches through the multipath form, which
     * names the branch as <0;1> and the index as a wildcard, so the receive and change chains come from one string.
     */
    val descriptor: String = run {
        val key = "[%08x/%s]%s/<0;1>/*".format(
            masterFingerprint,
            path.asString('\'').removePrefix("m/"),
            xpub.encode(network.xpubVersion),
        )
        val body = when (scriptType) {
            ScriptType.LEGACY -> "pkh($key)"
            ScriptType.NESTED_SEGWIT -> "sh(wpkh($key))"
            ScriptType.NATIVE_SEGWIT -> "wpkh($key)"
            ScriptType.TAPROOT -> "tr($key)"
        }
        "$body#${Descriptor.checksum(body)}"
    }

    /**
     * The receive address at m/.../0/[index], derived from the public key alone. A watch-only copy shows this address without the seed.
     * @throws IllegalArgumentException if [index] is negative.
     */
    fun receiveAddress(index: Int): String = addressAt(BtcAccount.RECEIVE, index)

    /**
     * The change address at m/.../1/[index], derived from the public key alone. It is the address [BtcAccount.changeAddress] gives
     * for the same Account, so a watch-only copy can watch the change chain too.
     * @throws IllegalArgumentException if [index] is negative.
     */
    fun changeAddress(index: Int): String = addressAt(BtcAccount.CHANGE, index)

    private fun addressAt(branch: Int, index: Int): String {
        require(index >= 0) { "address index must be 0 or more" }
        return addressOf(scriptType, network, xpub.derivePublicKey(branch.toLong()).derivePublicKey(index.toLong()).publicKey)
    }
}

/**
 * The watch-only Accounts of a seed: one [BtcWatchOnlyAccount] per [ScriptType], which the AirVault pairing carries.
 */
object BtcWatchOnly {
    /** The four watch-only Accounts at [accountIndex] of [network], derived from a BIP39 [seed] of 16 to 64 bytes. */
    fun accounts(seed: ByteArray, network: BtcNetwork, accountIndex: Int = 0): List<BtcWatchOnlyAccount> =
        ScriptType.entries.map { BtcAccount.fromSeed(seed, network, it, accountIndex).watchOnly() }
}

/** The "i-n" sequence header that every frame of a multi-part UR carries after its type, as in ur:type/1-3/body. */
private val SEQUENCE_HEADER = Regex("""^UR:[A-Z0-9-]+/\d+-(\d+)/""", RegexOption.IGNORE_CASE)

/** The number of parts an animated UR has, read from the sequence header of one of its frames, or 1 for a single-part UR. */
internal fun sequenceLength(frame: String): Int = SEQUENCE_HEADER.find(frame)?.groupValues?.get(1)?.toInt() ?: 1
