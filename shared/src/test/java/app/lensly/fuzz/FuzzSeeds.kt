package app.lensly.fuzz

/**
 * Reads the committed seed corpora under `src/test/resources/fuzz-seeds/<target>`.
 *
 * Jazzer's own convention is `<package>/<Class>Inputs/<method>`, but it cannot enumerate a directory that lives
 * inside a jar, so each corpus carries an `index.txt` naming its files. Adding a seed means adding the file and
 * its name to the index.
 */
internal object FuzzSeeds {

    /** Cap borrowed from the production parsers. A seed larger than this is a mistake, not a test case. */
    private const val MAX_SEED_BYTES = 80 * 1024

    fun bytes(target: String): List<ByteArray> = names(target).map { name ->
        val path = "/fuzz-seeds/$target/$name"
        val seed = checkNotNull(FuzzSeeds::class.java.getResourceAsStream(path)) { "missing seed $path" }
            .use { it.readBytes() }
        require(seed.size <= MAX_SEED_BYTES) { "seed $path is ${seed.size} bytes, over $MAX_SEED_BYTES" }
        seed
    }

    private fun names(target: String): List<String> {
        val path = "/fuzz-seeds/$target/index.txt"
        val index = checkNotNull(FuzzSeeds::class.java.getResourceAsStream(path)) { "missing seed index $path" }
        return index.use { it.readBytes().decodeToString() }
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }
}
