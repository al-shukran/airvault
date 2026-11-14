package app.lensly.core.ur

/**
 * Keystone UR test vectors, copied byte-for-byte from KeystoneHQ/keystone-airgaped-base
 * packages/ur-registry-eth/__tests__ into src/test/resources/keystone.
 */
internal object KeystoneVectors {
    fun ur(name: String): String =
        checkNotNull(KeystoneVectors::class.java.getResource("/keystone/$name")) { "missing vector $name" }
            .readText()
            .trim()
}
