package app.airvault.core.conformance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 8.1: the signer side (core/signer-api, chains/<id>/common and chains/<id>/signer) has no network library. */
class NetworkDependencyTest {
    @Test
    fun theSignerClasspathHasNoNetworkLibrary() {
        val coordinates = testClasspathCoordinates()

        // The classpath holds the signer modules and the Kotlin stdlib, so a near-empty list means the scan saw nothing.
        assertTrue(coordinates.size > 5, "found ${coordinates.size} coordinates on the test classpath")
        assertEquals(emptyList(), networkDependencyViolations(coordinates))
    }

    @Test
    fun theSignerSourcesDoNotReachTheNetwork() {
        val root = File(System.getProperty("airvault.repoRoot"))
        val chains = root.resolve("chains").listFiles().orEmpty().filter { it.isDirectory }
        val signerDirs = listOf(root.resolve("core/signer-api/src/main")) +
            chains.flatMap { listOf(it.resolve("common/src/main"), it.resolve("signer/src/main")) }
        val sources = signerDirs.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in SOURCE_EXTENSIONS }.toList() }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

        assertTrue(sources.size > 5, "found ${sources.size} signer source files")
        assertEquals(emptyList(), networkSourceViolations(sources))
    }

    @Test
    fun aBrokenFixtureFailsTheCoordinateCheck() {
        val violations = networkDependencyViolations(
            listOf(
                "com.squareup.okhttp3:okhttp:4.12.0",
                "io.ktor:ktor-client-core-jvm:3.1.0",
                "com.squareup.retrofit2:retrofit:2.11.0",
                "org.bouncycastle:bcprov-jdk18on:1.87",
            ),
        )

        assertEquals(
            listOf(
                "com.squareup.okhttp3:okhttp:4.12.0",
                "io.ktor:ktor-client-core-jvm:3.1.0",
                "com.squareup.retrofit2:retrofit:2.11.0",
            ),
            violations,
        )
    }

    @Test
    fun aBrokenFixtureFailsTheSourceCheckAtEachNetworkUse() {
        val violations = networkSourceViolations(
            mapOf(
                "A.kt" to "import okhttp3.OkHttpClient",
                "B.kt" to "val c = java.net.http.HttpClient.newHttpClient()", // CI_ALLOW_NETWORK_REFERENCE: scanner fixture text
                "C.kt" to "val u = java.net.URI(\"https://example.invalid\")", // CI_ALLOW_NETWORK_REFERENCE: scanner fixture text
                "D.kt" to "import io.ktor.client.HttpClient",
                "E.kt" to "val c = java.net.HttpURLConnection::class", // CI_ALLOW_NETWORK_REFERENCE: scanner fixture text
                "F.kt" to "val u = java.net.URL(\"https://example.invalid\")", // CI_ALLOW_NETWORK_REFERENCE: scanner fixture text
            ),
        )

        assertEquals(listOf("A.kt:1", "B.kt:1", "D.kt:1", "E.kt:1", "F.kt:1"), violations)
    }

    /**
     * The "group:artifact" of every jar on the test classpath that Gradle's module cache holds. Its layout is
     * files-2.1/group/artifact/version/hash/file.jar. The jars carry no Maven metadata to read instead.
     */
    private fun testClasspathCoordinates(): List<String> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.endsWith(".jar") }
            .mapNotNull { jar ->
                val segments = File(jar).invariantSeparatorsPath.split('/')
                val at = segments.indexOf("files-2.1")
                if (at < 0 || segments.size < at + 5) null else "${segments[at + 1]}:${segments[at + 2]}"
            }
            .distinct()

    private companion object {
        val SOURCE_EXTENSIONS = setOf("kt", "java")
    }
}
