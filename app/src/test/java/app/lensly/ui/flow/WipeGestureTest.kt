package app.lensly.ui.flow

import android.content.Context
import android.content.SharedPreferences
import app.lensly.app.EntryGestureHolder
import app.lensly.app.EntryGestureStore
import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.camera.EntryGesture
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Wipe and the Hidden Entry Gesture: a real wipe resets it, and a wipe from the Decoy Vault leaves it alone. */
@RunWith(RobolectricTestRunner::class)
class WipeGestureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var prefs: SharedPreferences
    private lateinit var vault: Vault
    private lateinit var holder: VaultHolder
    private lateinit var gestures: EntryGestureHolder

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(EntryGestureStore.FILE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        vault = Vault(VaultLayout(File(tmp.root, "vault")), keys, clock, KDF)
        holder = VaultHolder(vault)
        gestures = EntryGestureHolder(EntryGestureStore(prefs))
    }

    @Test
    fun aRealWipeResetsTheEntryGestureToTheDefault() = runBlocking<Unit> {
        create()
        val real = unlocked(APP)
        assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() })
        assertTrue(gestures.save(EntryGesture.PINCH_TRIPLE_TAP))
        adopt(real)

        assertEquals(ShredOutcome.ERASED, WipeVaultPorts(holder, gestures).erase())

        assertEquals(EntryGesture.DEFAULT, gestures.current)
        assertEquals(EntryGesture.DEFAULT, EntryGestureStore(prefs).load())
        assertFalse(vault.exists())
    }

    @Test
    fun aWipeFromTheDecoyLeavesTheEntryGestureAsItWas() = runBlocking<Unit> {
        create()
        val real = unlocked(APP)
        val realWallet = real.createWallet()
        assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() })
        real.close()

        assertTrue(gestures.save(EntryGesture.PINCH_TRIPLE_TAP))
        adopt(unlocked(DURESS))

        assertEquals(ShredOutcome.ERASED, WipeVaultPorts(holder, gestures).erase())

        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, gestures.current)
        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, EntryGestureStore(prefs).load())
        assertTrue(vault.exists())

        val reopened = unlocked(APP)
        assertEquals(listOf(realWallet), reopened.listWallets())
        reopened.close()
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlocked(password: String): VaultSession {
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private fun adopt(session: VaultSession) {
        assertTrue(holder.adopt(session, holder.unlockEpoch()))
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
