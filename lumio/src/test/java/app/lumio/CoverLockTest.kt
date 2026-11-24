package app.lumio

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
import app.lensly.ui.navigation.InactivityLock
import app.lensly.ui.navigation.LenslyRoute
import app.lensly.ui.navigation.LockPolicy
import app.lensly.ui.navigation.PickerStop
import app.lensly.ui.navigation.ReturnToCameraWhenBackgrounded
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lumio.data.config.AutoLock
import app.lumio.data.config.LockSettings
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The automatic locks of Lumio's cover, with the policy the owner can set. The test clock drives the idle countdown, and synthetic
 * touches restart it. Each case builds the same navigation host as the cover, with the policy under test. Lumio's launches of its own
 * (the export picker, the scanners) are the [PickerStop] calls that the app makes around them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class CoverLockTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private lateinit var holder: VaultHolder

    @Before
    fun clearLaunches() {
        PickerStop.reset()
    }

    /**
     * A thirty-minute idle timeout with no lock on a leave, the timing the cases below count in. Lumio's own choices are checked in LockSettingsTest,
     * and the cover's mechanics do not depend on which choice is made.
     */
    private val defaults: LockPolicy = LockPolicy(idleTimeoutMs = 30 * MINUTE, lockWhenBackgrounded = false, ownLaunchesKeepUnlocked = true)
    private val immediately: LockPolicy = LockSettings(AutoLock.IMMEDIATELY).policy

    private fun showHiddenArea(policy: LockPolicy) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val context = LocalContext.current
            val controller = rememberNavController()
            nav = controller
            val vaultHolder = remember { VaultHolder(Vault(context)) }
            holder = vaultHolder
            ReturnToCameraWhenBackgrounded(controller, vaultHolder, policy)
            val touchModifier = InactivityLock(controller, vaultHolder, policy) { compose.mainClock.currentTime }
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

    private fun route(): String? = nav.currentBackStackEntry?.destination?.route

    private fun lifecycle(event: Lifecycle.Event) {
        compose.runOnIdle { (compose.activity.lifecycle as LifecycleRegistry).handleLifecycleEvent(event) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun touch() {
        compose.onNodeWithTag(ROOT).performTouchInput { down(center) }
        compose.onNodeWithTag(ROOT).performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
    }

    /**
     * Runs a [PickerStop] call on the UI thread, then lets the composition see it. The idle countdown reads the launch count in
     * composition, so the change must reach the composition before the next step of time.
     */
    private fun onUi(change: () -> Unit) {
        compose.runOnIdle(change)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun defaults_switchingAwayKeepsTheVaultOpen() {
        showHiddenArea(defaults)
        lifecycle(Lifecycle.Event.ON_STOP)
        lifecycle(Lifecycle.Event.ON_START)
        assertEquals(LenslyRoute.HIDDEN, route())
        assertEquals(0L, holder.unlockEpoch(), "switching apps must not lock with the switch off")
    }

    @Test
    fun defaults_thirtyMinutesWithoutATouchLocks() {
        showHiddenArea(defaults)
        compose.mainClock.advanceTimeBy(29 * MINUTE)
        assertEquals(LenslyRoute.HIDDEN, route())
        compose.mainClock.advanceTimeBy(2 * MINUTE)
        assertEquals(LenslyRoute.CAMERA, route())
        assertEquals(1L, holder.unlockEpoch())
    }

    @Test
    fun defaults_timeAwayInTheBackgroundCountsTowardTheTimeout() {
        showHiddenArea(defaults)
        compose.mainClock.advanceTimeBy(10 * MINUTE)
        lifecycle(Lifecycle.Event.ON_STOP)
        compose.mainClock.advanceTimeBy(19 * MINUTE)
        assertEquals(LenslyRoute.HIDDEN, route())
        compose.mainClock.advanceTimeBy(2 * MINUTE)
        assertEquals(LenslyRoute.CAMERA, route(), "the countdown ran through the stop")
        assertEquals(1L, holder.unlockEpoch())
    }

    @Test
    fun defaults_aTouchRestartsTheCountdown() {
        showHiddenArea(defaults)
        compose.mainClock.advanceTimeBy(20 * MINUTE)
        touch()
        compose.mainClock.advanceTimeBy(29 * MINUTE)
        assertEquals(LenslyRoute.HIDDEN, route())
        compose.mainClock.advanceTimeBy(2 * MINUTE)
        assertEquals(LenslyRoute.CAMERA, route())
    }

    @Test
    fun immediately_aStopLocksAtOnce() {
        showHiddenArea(immediately)
        lifecycle(Lifecycle.Event.ON_STOP)
        assertEquals(LenslyRoute.CAMERA, route())
        assertEquals(1L, holder.unlockEpoch())
    }

    @Test
    fun immediately_aPickersOwnStopNeverLocks() {
        showHiddenArea(immediately)
        onUi { PickerStop.arm() }
        lifecycle(Lifecycle.Event.ON_STOP)
        assertEquals(LenslyRoute.HIDDEN, route(), "the picker's own stop must not lock")
        assertEquals(0L, holder.unlockEpoch())

        onUi { PickerStop.disarm(compose.mainClock.currentTime) }
        lifecycle(Lifecycle.Event.ON_START)
        lifecycle(Lifecycle.Event.ON_STOP)
        assertEquals(LenslyRoute.CAMERA, route(), "a stop after the picker returned locks as usual")
        assertEquals(1L, holder.unlockEpoch())
    }

    @Test
    fun ownPickerDoesNotLockWhileItIsOpenEvenPastTheTimeout() {
        showHiddenArea(defaults)
        compose.mainClock.advanceTimeBy(10 * MINUTE)
        onUi { PickerStop.arm() }
        compose.mainClock.advanceTimeBy(40 * MINUTE)
        assertEquals(LenslyRoute.HIDDEN, route(), "the idle lock waits for an open picker")
        assertEquals(0L, holder.unlockEpoch())
    }

    @Test
    fun ownPickerReturnCountsAsActivity() {
        showHiddenArea(defaults)
        compose.mainClock.advanceTimeBy(10 * MINUTE)
        onUi { PickerStop.arm() }
        compose.mainClock.advanceTimeBy(40 * MINUTE)
        onUi { PickerStop.disarm(compose.mainClock.currentTime) }

        compose.mainClock.advanceTimeBy(29 * MINUTE)
        assertEquals(LenslyRoute.HIDDEN, route(), "the return restarts the thirty minutes")
        compose.mainClock.advanceTimeBy(2 * MINUTE)
        assertEquals(LenslyRoute.CAMERA, route())
        assertEquals(1L, holder.unlockEpoch())
    }

    @Test
    fun scannerHoldWaitsTheIdleLockLikeAPicker() {
        showHiddenArea(defaults)
        onUi { PickerStop.hold() }
        compose.mainClock.advanceTimeBy(40 * MINUTE)
        assertEquals(LenslyRoute.HIDDEN, route(), "a scanner on screen keeps the vault open")
        onUi { PickerStop.release(compose.mainClock.currentTime) }
        compose.mainClock.advanceTimeBy(29 * MINUTE)
        assertEquals(LenslyRoute.HIDDEN, route())
        compose.mainClock.advanceTimeBy(2 * MINUTE)
        assertEquals(LenslyRoute.CAMERA, route())
    }

    @Test
    fun lensly_twoMinutesWithoutInputStillLocks() {
        showHiddenArea(LockPolicy.LENSLY)
        compose.mainClock.advanceTimeBy(INACTIVITY_TWO_MINUTES + 1L)
        assertEquals(LenslyRoute.CAMERA, route())
        assertEquals(1L, holder.unlockEpoch())
    }

    @Test
    fun lensly_aLaunchDoesNotPauseItsIdleLock() {
        showHiddenArea(LockPolicy.LENSLY)
        onUi { PickerStop.arm() }
        compose.mainClock.advanceTimeBy(INACTIVITY_TWO_MINUTES + 1L)
        assertEquals(LenslyRoute.CAMERA, route(), "Lensly's idle lock has always run through its own pickers")
    }

    private companion object {
        const val ROOT = "cover-lock-root"
        const val MINUTE = 60_000L
        const val INACTIVITY_TWO_MINUTES = 2 * MINUTE
    }
}
