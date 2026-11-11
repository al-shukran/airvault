package app.lensly.core.wallet

/**
 * Published BIP39 and BIP32 vectors from `src/test/resources/vectors`, copied unchanged from the
 * upstream files. Each directory's `README.md` records the source URL and retrieval date.
 */
internal object PublishedWalletVectors {

    class Bip39(
        val entropy: String,
        val mnemonic: List<String>,
        val seed: String,
        val passphrase: String,
    )

    class Bip32(
        val vector: Int,
        val seed: String,
        val masterXpub: String,
        val masterPublicKey: String,
        val masterFingerprint: String,
    )

    fun bip39(): List<Bip39> = rows("bip39").map { row ->
        require(row.size == 4) { "BIP39 row must have 4 columns, found ${row.size}" }
        Bip39(entropy = row[0], mnemonic = row[1].split(' '), seed = row[2], passphrase = row[3])
    }

    fun bip32(): List<Bip32> = rows("bip32").map { row ->
        require(row.size == 5) { "BIP32 row must have 5 columns, found ${row.size}" }
        Bip32(
            vector = row[0].toInt(),
            seed = row[1],
            masterXpub = row[2],
            masterPublicKey = row[3],
            masterFingerprint = row[4],
        )
    }

    private fun rows(standard: String): List<List<String>> {
        val path = "/vectors/$standard/vectors.txt"
        val text = checkNotNull(PublishedWalletVectors::class.java.getResource(path)) { "missing vector $path" }
            .readText()
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .toList()
    }
}
