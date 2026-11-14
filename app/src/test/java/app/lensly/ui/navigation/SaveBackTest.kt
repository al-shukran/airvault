package app.lensly.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.SaveState
import app.lensly.app.VaultHolder
import app.lensly.app.WalletDraftViewModel
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.theme.LenslyTheme
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Back during a save is ignored while the save runs, and the screen says "Saving…". The press does not cancel the save, and
 * Back works again once the save has ended.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-port-mdpi")
class SaveBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    // BIP39 vector for 256 zero bits of entropy.
    private val artWords = List(23) { "abandon" } + "art"

    private lateinit var nav: NavHostController

    @Test
    fun backDuringANameSaveIsIgnoredAndTheSaveRunsToItsEnd() {
        val model = openHome()
        model.draft.setTypedWords(artWords)
        compose.runOnIdle { nav.navigate(HiddenRoute.NAME) }
        compose.waitForIdle()
        val release = CompletableDeferred<Unit>()
        var finished = false
        compose.runOnIdle {
            model.save("WALLET") { _, _, _, _ ->
                release.await()
                finished = true
                "00000000-0000-4000-8000-000000000007"
            }
        }

        awaitSaving(model)
        compose.onNodeWithText("Saving…", ignoreCase = true).assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(HiddenRoute.NAME, nav.currentBackStackEntry?.destination?.route, "Back does not leave the name screen")
        assertEquals(SaveState.Saving, model.saveState.value, "Back does not cancel the save")

        release.complete(Unit)
        awaitSave(model) { it is SaveState.Saved }
        assertTrue(finished, "the write ran to its end")
        assertEquals(SaveState.Saved("00000000-0000-4000-8000-000000000007"), model.saveState.value)
    }

    @Test
    fun backWorksAgainOnceAFailedNameSaveHasEnded() {
        val model = openHome()
        model.draft.setTypedWords(artWords)
        compose.runOnIdle { nav.navigate(HiddenRoute.NAME) }
        compose.waitForIdle()
        val release = CompletableDeferred<Unit>()
        compose.runOnIdle {
            model.save("WALLET") { _, _, _, _ ->
                release.await()
                throw IOException("the write failed")
            }
        }
        awaitSaving(model)
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(HiddenRoute.NAME, nav.currentBackStackEntry?.destination?.route)

        release.complete(Unit)
        awaitSave(model) { it == SaveState.Failed }
        compose.onNodeWithText(SAVE_FAILED_MESSAGE).assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertNotEquals(HiddenRoute.NAME, nav.currentBackStackEntry?.destination?.route, "Back leaves once the save has ended")
    }

    @Test
    fun backDuringAPassphraseBackupSaveIsIgnoredAndTheSaveRunsToItsEnd() {
        val model = openHome()
        model.draft.setTypedWords(artWords)
        model.draft.setPassphrase("correct horse")
        compose.runOnIdle { nav.navigate(HiddenRoute.PASSPHRASE_BACKUP) }
        compose.waitForIdle()
        val release = CompletableDeferred<Unit>()
        var finished = false
        compose.runOnIdle {
            model.save("WALLET") { _, _, _, _ ->
                release.await()
                finished = true
                "00000000-0000-4000-8000-000000000008"
            }
        }

        awaitSaving(model)
        compose.onNodeWithText("Saving…", ignoreCase = true).assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(HiddenRoute.PASSPHRASE_BACKUP, nav.currentBackStackEntry?.destination?.route)

        release.complete(Unit)
        awaitSave(model) { it is SaveState.Saved }
        assertTrue(finished, "the write ran to its end")
    }

    @Test
    fun backDuringAKeyBackupSaveIsIgnoredAndTheSaveRunsToItsEnd() {
        val model = openHome()
        model.draft.setSingleKey(ByteArray(32) { (it + 1).toByte() })
        compose.runOnIdle { nav.navigate(HiddenRoute.KEY_BACKUP) }
        compose.waitForIdle()
        val release = CompletableDeferred<Unit>()
        var finished = false
        compose.runOnIdle {
            model.saveSingleKey("WALLET") { _, _ ->
                release.await()
                finished = true
                "00000000-0000-4000-8000-000000000009"
            }
        }

        awaitSaving(model)
        compose.onNodeWithText("Saving…", ignoreCase = true).assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(HiddenRoute.KEY_BACKUP, nav.currentBackStackEntry?.destination?.route)

        release.complete(Unit)
        awaitSave(model) { it is SaveState.Saved }
        assertTrue(finished, "the write ran to its end")
    }

    /** Shows the hidden graph with an open vault, so HOME composes, and returns the flow's draft model. */
    private fun openHome(): WalletDraftViewModel {
        val holder = openHolder()
        compose.setContent {
            LenslyTheme {
                nav = rememberNavController()
                val controller = remember { nav }
                NavHost(navController = controller, startDestination = LenslyRoute.CAMERA) {
                    composable(LenslyRoute.CAMERA) {}
                    hiddenGraph(controller, holder)
                }
            }
        }
        compose.runOnIdle { nav.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true } }
        compose.waitForIdle()
        assertEquals(HiddenRoute.HOME, nav.currentBackStackEntry?.destination?.route)
        return ViewModelProvider(nav.getBackStackEntry(LenslyRoute.HIDDEN))[WalletDraftViewModel::class.java]
    }

    /** Waits until the save reaches a state that [done] accepts. The save resumes on the main looper, which is idled here. */
    private fun awaitSave(model: WalletDraftViewModel, done: (SaveState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done(model.saveState.value)) {
            check(System.currentTimeMillis() < deadline) { "the save stopped at ${model.saveState.value}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun awaitSaving(model: WalletDraftViewModel) = awaitSave(model) { it == SaveState.Saving }

    /** A vault with an open session, so HOME composes instead of sending the owner back to the gate. */
    private fun openHolder(): VaultHolder {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        val vault = Vault(
            VaultLayout(File(tmp.root, "vault")),
            MemoryKeystore(),
            FakeClock(),
            KdfParams(memoryKiB = 8 * 1024, iterations = 1),
        )
        return VaultHolder(vault).also { it.adopt(session, it.unlockEpoch()) }
    }
}
