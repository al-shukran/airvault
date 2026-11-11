package app.lensly.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.Vault
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two automatic locks in the navigation host. The test clock drives the inactivity countdown, and synthetic touches
 * restart it. A lifecycle stop must lock at once, with no clock time at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class AutoLockTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private lateinit var holder: VaultHolder

    private fun showNavHost() {
        PickerStop.disarm()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val context = LocalContext.current
            val controller = rememberNavController()
            nav = controller
            val vaultHolder = remember { VaultHolder(Vault(context)) }
            holder = vaultHolder
            ReturnToCameraWhenBackgrounded(controller, vaultHolder)
            val touchModifier = InactivityLock(controller, vaultHolder)
            Box(modifier = Modifier.fillMaxSize().then(touchModifier).testTag(ROOT)) {
                NavHost(navController = controller, startDestination = LenslyRoute.CAMERA) {
                    composable(LenslyRoute.CAMERA) {}
                    composable(LenslyRoute.HIDDEN) {}
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true } }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun twoMinutesWithoutInputLocksTheVault() {
        showNavHost()

        compose.mainClock.advanceTimeBy(INACTIVITY_LOCK_MS + 1L)
        compose.mainClock.advanceTimeByFrame()

        assertEquals(LenslyRoute.CAMERA, nav.currentBackStackEntry?.destination?.route)
        assertEquals(1L, holder.unlockEpoch(), "leaving the hidden area must lock the vault")
    }

    @Test
    fun inputBeforeTwoMinutesRestartsTheTimer() {
        showNavHost()

        compose.mainClock.advanceTimeBy(INACTIVITY_LOCK_MS - 10_000L)
        compose.onNodeWithTag(ROOT).performTouchInput { down(center) }
        compose.onNodeWithTag(ROOT).performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()

        // 220 s since entering, but only 110 s since the touch.
        compose.mainClock.advanceTimeBy(INACTIVITY_LOCK_MS - 10_000L)
        assertEquals(LenslyRoute.HIDDEN, nav.currentBackStackEntry?.destination?.route)
        assertEquals(0L, holder.unlockEpoch())

        // 10 s later the touch's own two minutes are up.
        compose.mainClock.advanceTimeBy(20_000L)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(LenslyRoute.CAMERA, nav.currentBackStackEntry?.destination?.route)
        assertEquals(1L, holder.unlockEpoch())
    }

    @Test
    fun backgroundingLocksTheVaultImmediately() {
        showNavHost()

        compose.runOnIdle {
            (compose.activity.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(LenslyRoute.CAMERA, nav.currentBackStackEntry?.destination?.route)
        assertEquals(1L, holder.unlockEpoch(), "a stop must not wait for the inactivity timer")
    }

    private companion object {
        const val ROOT = "auto-lock-root"
    }
}
