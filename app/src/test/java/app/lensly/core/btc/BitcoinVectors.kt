package app.lensly.core.btc

import java.util.Properties

/** The known answers in `vectors/bitcoin/vectors.txt`. The file is generated, and its BIP values are checked against the BIP text. */
internal object BitcoinVectors {
    private val values: Properties by lazy {
        val stream = checkNotNull(BitcoinVectors::class.java.getResourceAsStream("/vectors/bitcoin/vectors.txt")) {
            "missing vectors/bitcoin/vectors.txt"
        }
        Properties().apply { stream.use { load(it) } }
    }

    operator fun get(key: String): String =
        checkNotNull(values.getProperty(key)) { "missing $key in vectors/bitcoin/vectors.txt" }

    /** The 64-byte BIP39 seed of the published "abandon ... about" mnemonic, with no passphrase. */
    fun seed(): ByteArray = hexBytes(get("seed.abandon-about"))

    fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }

    /** The name a network has in the key names of vectors.txt. */
    fun networkName(network: BtcNetwork): String = network.name.lowercase()

    /** The name a Script Type has in the key names of vectors.txt. */
    fun typeName(type: ScriptType): String = when (type) {
        ScriptType.LEGACY -> "legacy"
        ScriptType.NESTED_SEGWIT -> "nested"
        ScriptType.NATIVE_SEGWIT -> "native"
        ScriptType.TAPROOT -> "taproot"
    }
}
