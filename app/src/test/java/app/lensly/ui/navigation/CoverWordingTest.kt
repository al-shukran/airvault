package app.lensly.ui.navigation

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The camera cover opens without the secret gesture, so what it shows must not say what the vault holds. This reads the string
 * literals of the cover's sources, and of the launcher label, and fails on a forbidden word. It is what keeps the viewfinder banner
 * "Never photograph recovery words." from coming back. Comments are not read: only what a user can see counts.
 */
class CoverWordingTest {

    @Test
    fun coverStringsUseNoForbiddenWord() {
        val offenders = coverFiles().flatMap { file ->
            literalsIn(file).filter { forbidden.containsMatchIn(it) }.map { "${file.name}: \"$it\"" }
        }
        assertTrue(offenders.isEmpty(), "cover strings use a forbidden word: $offenders")
    }

    @Test
    fun theLauncherLabelUsesNoForbiddenWord() {
        val values = File("src/main/res/values/strings.xml").readText()
        val label = Regex("""<string name="app_name">([^<]*)</string>""").find(values)?.groupValues?.get(1).orEmpty()
        assertTrue(label.isNotEmpty() && !forbidden.containsMatchIn(label), "the launcher label is \"$label\"")
    }

    @Test
    fun theScanReadsTheViewfinderLabels() {
        // Guards against the literal scan going blind: a label the viewfinder shows must be found.
        assertTrue("Open gallery" in literalsIn(File(CAMERA_DIR, "CameraScreen.kt")), "the scan reads CameraScreen.kt")
    }

    private fun coverFiles(): List<File> =
        checkNotNull(CAMERA_DIR.listFiles { file -> file.extension == "kt" }) { "no camera sources at $CAMERA_DIR" }.toList() +
            COVER_SOURCES.map { File(SHARED_LENSLY, it) }

    private companion object {
        /** Words that name what the vault holds. Whole words, plurals included, so "design" and "keyboard" pass. */
        val forbidden = Regex(
            """\b(wallets?|recovery|seeds?|mnemonics?|crypto\w*|coins?|tokens?|signs?|signed|signing|keys?|bitcoin|ethereum|solana|algorand|lumio|pairs?|paired|pairing|addresses?|transactions?)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** A string literal, or a line comment, which the scan skips past so a `//` inside a string is not read as a comment. */
        val token = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|//.*")

        val SHARED_LENSLY = File("../shared/src/main/java/app/lensly")
        val CAMERA_DIR = File(SHARED_LENSLY, "ui/camera")

        /** The cover's other sources: the gate, the gallery gate, the device prompt and the components the cover draws with. */
        val COVER_SOURCES = listOf(
            "ui/gate/UnlockScreen.kt",
            "ui/gate/PasswordField.kt",
            "ui/navigation/CoverNavHost.kt",
            "ui/navigation/GalleryGate.kt",
            "core/vault/DeviceCredentialPrompt.kt",
            "ui/components/DotHeadline.kt",
            "ui/components/DotProgress.kt",
            "ui/components/EmptyState.kt",
            "ui/components/ErrorState.kt",
            "ui/components/InlineBanner.kt",
            "ui/components/KeyboardHost.kt",
            "ui/components/LenslyScreen.kt",
            "ui/components/PillButton.kt",
            "ui/components/SecureKeyboard.kt",
        )

        /** The string literals in [file], comments left out. */
        fun literalsIn(file: File): List<String> = file.readLines()
            .filterNot { line -> line.trimStart().let { it.startsWith("*") || it.startsWith("/*") } }
            .flatMap { line ->
                token.findAll(line).map { it.value }.filter { it.startsWith('"') }.map { it.drop(1).dropLast(1) }.toList()
            }
    }
}
