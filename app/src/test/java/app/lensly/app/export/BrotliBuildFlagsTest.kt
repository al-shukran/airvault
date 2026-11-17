package app.lensly.app.export

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/** The native Brotli encoder is built with stack canaries and full RELRO. Dropping either flag would not fail any other test. */
class BrotliBuildFlagsTest {

    private val cmakeLists = File("src/main/cpp/CMakeLists.txt").readText()

    @Test
    fun compileOptionsEnableStackProtectorStrong() {
        assertTrue(
            Regex("""target_compile_options\([^)]*-fstack-protector-strong""").containsMatchIn(cmakeLists),
            "the encoder is compiled with -fstack-protector-strong",
        )
    }

    @Test
    fun linkOptionsEnableRelroAndBindNow() {
        assertTrue(
            Regex("""target_link_options\([^)]*-Wl,-z,relro,-z,now""").containsMatchIn(cmakeLists),
            "the encoder is linked with -Wl,-z,relro,-z,now",
        )
    }
}
