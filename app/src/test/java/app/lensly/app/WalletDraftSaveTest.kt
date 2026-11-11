package app.lensly.app

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The save of a draft's wallet. It runs in the model, so the screen that started it can leave without cancelling it. The
 * words are wiped only after the write has returned, and a failed write keeps them for another save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-port-mdpi")
class WalletDraftSaveTest {

    @get:Rule
    val compose = createComposeRule()

    // BIP39 vector for 256 zero bits of entropy.
    private val artWords = List(23) { "abandon" } + "art"

    @Test
    fun aSaveRunsToItsEndAfterTheScreenThatStartedItLeavesAndWipesTheWordsAfter() {
        val model = WalletDraftViewModel()
        model.draft.setTypedWords(artWords)
        val release = CompletableDeferred<Unit>()
        var showing by mutableStateOf(true)
        compose.setContent {
            if (showing) {
                val state by model.saveState.collectAsState()
                Text(state.toString())
            }
        }
        compose.runOnIdle {
            model.save("WALLET") { _, _, _, _ ->
                release.await()
                "wallet-1"
            }
        }
        // The screen that started the save leaves while the write is still suspended, as a rotation or a Back press does.
        compose.runOnIdle { showing = false }
        compose.waitForIdle()

        assertEquals(SaveState.Saving, model.saveState.value)
        assertEquals(artWords, model.draft.words.toList(), "the words stay until the write returns")
        release.complete(Unit)
        awaitSave(model) { it is SaveState.Saved }
        assertEquals(SaveState.Saved("wallet-1"), model.saveState.value)
        assertTrue(model.draft.words.isEmpty(), "the words are wiped once the write has returned")
    }

    @Test
    fun aWipeDuringTheWriteDoesNotChangeTheWordsTheWriteReceives() {
        val model = WalletDraftViewModel()
        model.draft.setTypedWords(artWords)
        val release = CompletableDeferred<Unit>()
        var received: List<String>? = null
        compose.runOnIdle {
            model.save("WALLET") { _, _, words, _ ->
                release.await()
                received = words
                "wallet-2"
            }
        }
        // A HOME arrival, or the graph closing, wipes the draft while the write is still running.
        compose.runOnIdle { model.draft.wipe() }
        release.complete(Unit)
        awaitSave(model) { it is SaveState.Saved }
        assertEquals(artWords, received)
    }

    @Test
    fun aFailedSaveKeepsTheWordsAndASecondSaveWritesThemAgain() {
        val model = WalletDraftViewModel()
        model.draft.setTypedWords(artWords)
        compose.runOnIdle {
            model.save("WALLET") { _, _, _, _ -> throw IOException("the write failed") }
        }
        awaitSave(model) { it == SaveState.Failed }
        assertEquals(artWords, model.draft.words.toList(), "a failed write keeps the words")

        val written = mutableListOf<List<String>>()
        compose.runOnIdle {
            model.save("WALLET") { _, _, words, _ ->
                written += words
                "wallet-3"
            }
        }
        awaitSave(model) { it is SaveState.Saved }
        assertEquals(listOf(artWords), written, "the retry writes the same words")
        assertTrue(model.draft.words.isEmpty(), "the words are wiped after the retry succeeds")
    }

    @Test
    fun aVaultThatClosesDuringTheWriteReportsLockedAndKeepsTheWords() {
        val model = WalletDraftViewModel()
        model.draft.setTypedWords(artWords)
        compose.runOnIdle {
            model.save("WALLET") { _, _, _, _ -> throw IllegalStateException("the session is closed") }
        }
        awaitSave(model) { it == SaveState.Locked }
        assertEquals(artWords, model.draft.words.toList(), "a closed vault keeps the words")
    }

    @Test
    fun aSecondSaveWhileTheFirstRunsWritesNothing() {
        val model = WalletDraftViewModel()
        model.draft.setTypedWords(artWords)
        val release = CompletableDeferred<Unit>()
        var writes = 0
        compose.runOnIdle {
            model.save("WALLET") { _, _, _, _ ->
                writes++
                release.await()
                "wallet-4"
            }
            model.save("WALLET") { _, _, _, _ ->
                writes++
                "wallet-5"
            }
        }
        release.complete(Unit)
        awaitSave(model) { it is SaveState.Saved }
        assertEquals(1, writes, "the second call does nothing while the first save runs")
        assertEquals(SaveState.Saved("wallet-4"), model.saveState.value)
    }

    /**
     * Waits until the save reaches a state that [done] accepts. The save resumes on the main looper, which Robolectric runs
     * only when it is idled, so the wait idles it.
     */
    private fun awaitSave(model: WalletDraftViewModel, done: (SaveState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done(model.saveState.value)) {
            check(System.currentTimeMillis() < deadline) { "the save stopped at ${model.saveState.value}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }
}
