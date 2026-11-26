package app.lensly.ui.navigation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.BiometricCrypto
import app.lensly.core.vault.BiometricUnlock
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.WrappedKey
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The unlock page and biometric unlock (issue #783). The page never opens the biometric prompt by itself, so someone who forces the owner
 * to unlock cannot see that biometric unlock is on. Done with nothing typed opens it, and does nothing when biometric unlock is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-port-mdpi")
class UnlockBiometricTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // The v2 rule resumes the gate's coroutines on the main thread, as the app does.
    @get:Rule
    val compose = createComposeRule()

    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault
    private lateinit var holder: VaultHolder
    private lateinit var unlock: BiometricUnlock
    private val biometric = CountingBiometric()
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, MemoryKeystore(), FakeClock(), KdfParams(memoryKiB = 8 * 1024, iterations = 1))
        assertEquals(CreateResult.Created, runBlocking { vault.create(PASSWORD.toCharArray()) })
        unlock = BiometricUnlock(File(layout.root, BiometricUnlock.BLOB_FILE_NAME), biometric)
        holder = VaultHolder(vault)
    }

    private fun turnBiometricOn() {
        val session = assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(PASSWORD.toCharArray()) }).session
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()
    }

    private fun showUnlockPage(biometricUnlock: BiometricUnlock? = unlock) {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = LenslyRoute.CAMERA) {
                composable(LenslyRoute.CAMERA) {}
                hiddenArea(nav, holder, home = HOME, appName = "Test", biometric = biometricUnlock) { composable(HOME) {} }
            }
        }
        compose.runOnUiThread { nav.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true } }
        compose.waitUntil(timeoutMillis = 5_000) { route() == GateRoutes.UNLOCK }
        compose.waitForIdle()
    }

    private fun route(): String? = nav.currentBackStackEntry?.destination?.route

    @Test
    fun theUnlockPageDoesNotOpenTheBiometricPromptByItself() {
        turnBiometricOn()

        showUnlockPage()

        assertEquals(0, biometric.prompts)
        assertEquals(GateRoutes.UNLOCK, route())
        assertNull(holder.openSession())
    }

    @Test
    fun doneWithNothingTypedOpensThePromptAndAPassOpensTheRealVault() {
        turnBiometricOn()
        showUnlockPage()

        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { route() == HOME }

        assertEquals(1, biometric.prompts)
        assertTrue(!assertNotNull(holder.openSession()).isDecoy)
    }

    @Test
    fun aCancelledPromptLeavesTheUnlockPage() {
        turnBiometricOn()
        biometric.passes = false
        showUnlockPage()

        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { biometric.prompts == 1 }
        compose.waitForIdle()

        assertEquals(GateRoutes.UNLOCK, route())
        assertNull(holder.openSession())
    }

    @Test
    fun doneWithNothingTypedDoesNothingWhileBiometricUnlockIsOff() {
        showUnlockPage()

        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitForIdle()

        assertEquals(0, biometric.prompts)
        assertEquals(GateRoutes.UNLOCK, route())
        assertNull(holder.openSession())
    }

    @Test
    fun doneWithNothingTypedDoesNothingOnTheLenslyPathWithNoBiometricUnlock() {
        turnBiometricOn()
        showUnlockPage(biometricUnlock = null)

        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitForIdle()

        assertEquals(0, biometric.prompts)
        assertEquals(GateRoutes.UNLOCK, route())
        assertNull(holder.openSession())
    }

    @Test
    fun aFailedBiometricTurnsBiometricUnlockOffAndCountsNoStrike() {
        turnBiometricOn()
        biometric.fails = true
        showUnlockPage()
        val triesBefore = runBlocking { vault.triesBeforeErase() }

        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !unlock.isOn() }
        compose.waitForIdle()

        assertEquals(GateRoutes.UNLOCK, route())
        assertNull(holder.openSession())
        assertEquals(triesBefore, runBlocking { vault.triesBeforeErase() })
    }

    /** A pad in place of the Keystore cipher, and a prompt that passes or cancels as the test says. It counts the prompts. */
    private class CountingBiometric : BiometricCrypto {
        var passes = true
        var fails = false
        var prompts = 0
        private val pad = ByteArray(64) { (it * 37 + 11).toByte() }

        override fun available(): Boolean = true

        override fun createKey(): Boolean = true

        override suspend fun wrap(plain: ByteArray): WrappedKey = WrappedKey(ByteArray(12) { (it + 1).toByte() }, xor(plain))

        override suspend fun unwrap(wrapped: WrappedKey): ByteArray? {
            prompts++
            if (fails) throw IllegalStateException("key invalidated")
            return if (passes) xor(wrapped.ciphertext) else null
        }

        override fun deleteKey() = Unit

        private fun xor(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor pad[it].toInt()).toByte() }
    }

    private companion object {
        const val HOME = "home"
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
    }
}
