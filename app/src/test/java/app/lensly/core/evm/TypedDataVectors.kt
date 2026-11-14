package app.lensly.core.evm

/**
 * The published EIP-712 values in `src/test/resources/vectors/eip712/vectors.txt`, one key=value per line. The README beside it
 * names the sources, so the values are not written into test code.
 */
internal object TypedDataVectors {
    private val values: Map<String, String> by lazy {
        val text = checkNotNull(TypedDataVectors::class.java.getResource("/vectors/eip712/vectors.txt")) {
            "missing vectors/eip712/vectors.txt"
        }.readText()
        text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
    }

    /** The value of [key], such as "signingKey". */
    fun value(key: String): String = checkNotNull(values[key]) { "missing $key in vectors/eip712/vectors.txt" }
}
