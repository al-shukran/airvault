package app.lensly.ui.flow

import android.content.Context
import app.lensly.app.DecoyVault
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
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Hidden Entry Gesture is the real vault's setting, kept outside the vault. The real vault saves it. A decoy session
 * reports the same success, but it writes nothing, so a coerced change never reaches the real entry.
 */
@RunWith(RobolectricTestRunner::class)
class EntryGestureChangePortsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private lateinit var store: EntryGestureStore
    private lateinit var gestures: EntryGestureHolder
    private lateinit var vault: Vault
    private lateinit var holder: VaultHolder
    private lateinit var ports: EntryGestureChangePorts

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences(EntryGestureStore.FILE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = EntryGestureStore(prefs)
        gestures = EntryGestureHolder(store)

        vault = Vault(VaultLayout(File(tmp.root, "vault")), MemoryKeystore(), clock, KDF)
        holder = VaultHolder(vault)
        runBlocking {
            assertEquals(CreateResult.Created, vault.create(APP.toCharArray()))
            val real = open(APP)
            assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { DecoyVault.populate(it) })
            real.close()
        }
        ports = EntryGestureChangePorts(holder, gestures)
    }

    @Test
    fun aRealSessionSavesTheGestureToTheStore() {
        holder.adopt(open(APP), holder.unlockEpoch())

        assertTrue(runBlocking { ports.save(EntryGesture.PINCH_TRIPLE_TAP) })

        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, store.load())
        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, gestures.current)
    }

    @Test
    fun aDecoySessionReportsTheSaveAsDoneButWritesNothing() {
        // The real vault saves a gesture first, so there is a real value to protect.
        holder.adopt(open(APP), holder.unlockEpoch())
        assertTrue(runBlocking { ports.save(EntryGesture.THUMBNAIL_HOLD_SWIPE) })
        holder.lock()

        holder.adopt(open(DURESS), holder.unlockEpoch())
        assertTrue(runBlocking { ports.save(EntryGesture.SWITCH_THEN_SHUTTER_HOLD) })

        assertEquals(EntryGesture.THUMBNAIL_HOLD_SWIPE, store.load())
        assertEquals(EntryGesture.THUMBNAIL_HOLD_SWIPE, gestures.current)
    }

    private fun open(password: String): VaultSession = runBlocking {
        (vault.unlock(password.toCharArray()) as UnlockResult.Unlocked).session
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
