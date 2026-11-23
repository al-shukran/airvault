package app.lensly.ui.camera

import app.lensly.core.vault.MediaEnvelope
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.WALLET_PAD_FILE_NAME
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The names the photo store and the vault layout put on disk. None may say what a file holds, whose it is, or when it was taken: no kind
 * word, no decoy or wallet word, no key word, and no timestamp.
 */
class PhotoFileNamesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun noNameOnDiskSaysWhatAFileHoldsWhoseItIsOrWhenItWasTaken() {
        val root = folder.root
        val layout = VaultLayout(File(root, "vault"), File(root, "photos"))
        layout.makePhotoFolders()
        val privateKey = MediaEnvelope.newPrivateKey()
        layout.photoKeyFile(decoy = false).writeBytes(MediaEnvelope.publicKeyOf(privateKey))
        layout.photoKeyFile(decoy = true).writeBytes(MediaEnvelope.publicKeyOf(MediaEnvelope.newPrivateKey()))
        val store = PhotoStore.of(layout, decoy = false, privateKey = privateKey)
        assertTrue(store.savePhoto(Random(1).nextBytes(300), nowMs = 1_234_567_890_123L))
        val recording = folder.newFile("recording.mp4").apply { writeBytes(Random(2).nextBytes(300)) }
        assertTrue(store.saveVideo(recording, nowMs = 1_234_567_891_000L))
        assertEquals(2, layout.photoDir(decoy = false).listFiles().orEmpty().size, "the media must be on disk for the check to mean anything")

        val onDisk = layout.photos.walkTopDown().drop(1).toList()
        val produced = onDisk + listOf(
            layout.header,
            layout.vaultKey,
            layout.wallets,
            layout.vaultRecords,
            layout.reviewPool,
            File(layout.root, WALLET_PAD_FILE_NAME),
        )

        for (file in produced) {
            val name = file.relativeTo(root).path
            for (token in LEAKING_TOKENS) {
                assertFalse(name.contains(token, ignoreCase = true), "\"$name\" carries \"$token\"")
            }
            assertFalse(TIMESTAMP.containsMatchIn(name), "\"$name\" carries a time")
        }
        assertTrue(layout.photoDir(decoy = true).isDirectory, "both media folders exist from setup")
    }

    private companion object {
        /** Tokens that name a kind, an owner, a key, or a wallet. Hex names can contain none of them. */
        val LEAKING_TOKENS = listOf("decoy", "real", "duress", "wallet", "pub", "key", "img", "vid", "sealed")

        val TIMESTAMP = Regex("""\d{8}_\d{6}""")
    }
}
