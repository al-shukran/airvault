package app.lensly.core.evm

/**
 * Published EIP-155 and EIP-1559 signing examples from `src/test/resources/vectors`, copied from
 * the upstream sources. Each directory's `README.md` records the source URL and retrieval date.
 */
internal object PublishedSigningVectors {

    class Eip155(
        val unsigned: String,
        val signingHash: String,
        val privateKey: String,
        val signed: String,
    )

    class Eip1559(
        val chainId: Long,
        val nonce: Long,
        val value: Long,
        val gasLimit: Long,
        val maxPriorityFeePerGas: Long,
        val maxFeePerGas: Long,
        val to: String,
        val privateKey: String,
        val signedTx: String,
    )

    fun eip155(): Eip155 {
        val fields = rows("eip155").associate { row ->
            require(row.size == 2) { "EIP-155 row must be key=value, found ${row.size} columns" }
            row[0] to row[1]
        }
        return Eip155(
            unsigned = fields.required("unsigned"),
            signingHash = fields.required("signingHash"),
            privateKey = fields.required("privateKey"),
            signed = fields.required("signed"),
        )
    }

    fun eip1559(): List<Eip1559> = rows("eip1559").map { row ->
        require(row.size == 9) { "EIP-1559 row must have 9 columns, found ${row.size}" }
        Eip1559(
            chainId = row[0].toLong(),
            nonce = row[1].toLong(),
            value = row[2].toLong(),
            gasLimit = row[3].toLong(),
            maxPriorityFeePerGas = row[4].toLong(),
            maxFeePerGas = row[5].toLong(),
            to = row[6],
            privateKey = row[7],
            signedTx = row[8],
        )
    }

    private fun Map<String, String>.required(key: String): String =
        checkNotNull(this[key]) { "missing $key in vectors/eip155/vectors.txt" }

    private fun rows(standard: String): List<List<String>> {
        val path = "/vectors/$standard/vectors.txt"
        val text = checkNotNull(PublishedSigningVectors::class.java.getResource(path)) { "missing vector $path" }
            .readText()
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(if (standard == "eip155") '=' else '\t') }
            .toList()
    }
}
