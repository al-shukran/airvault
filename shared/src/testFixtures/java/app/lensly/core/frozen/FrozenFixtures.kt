package app.lensly.core.frozen

import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR

/**
 * The frozen Lensly fixtures in resources/frozen-lensly, read by the tests of both apps. They are read, never written: a test that fails on one
 * has found a change that breaks a frozen Lensly. See the README next to them.
 */
object FrozenFixtures {
    /** The UR that the frames of the fixture [name] carry, one frame per line of its file. */
    fun ur(name: String): UR {
        val lines = FrozenFixtures::class.java.getResourceAsStream("/frozen-lensly/$name.txt")
            ?.bufferedReader()?.use { it.readLines() }
            ?: error("The frozen fixture $name is missing.")
        val scanner = UrScanner()
        lines.filter { it.isNotBlank() }.forEach { scanner.receive(it) }
        return checkNotNull(scanner.result()) { "The frozen fixture $name is not a complete UR." }
    }
}
