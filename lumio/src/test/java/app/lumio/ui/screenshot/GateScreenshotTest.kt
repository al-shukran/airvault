package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.vault.BackupExclusion
import app.lensly.ui.navigation.UnlockUi
import app.lumio.ui.gate.ClearAirGateLook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gate that Lumio shows before the wallets, in Clear Air: the unlock page idle, while the key is derived, after a wrong password, during a
 * failure delay, and the unavailable page. The goldens are in the light theme, and the idle and unavailable pages also in the dark one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GateScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unlockIdle() = compose.clearAirSnapshot("lumio_unlock_idle") {
        ClearAirGateLook.Unlock(unlock(length = 6))
    }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun unlockIdleDark() = compose.clearAirSnapshot("lumio_unlock_idle_dark") {
        ClearAirGateLook.Unlock(unlock(length = 6))
    }

    @Test
    fun unlockWhileKeyIsDerived() = compose.clearAirSnapshot("lumio_unlock_unlocking") {
        ClearAirGateLook.Unlock(unlock(busy = true))
    }

    @Test
    fun unlockWrongTries() = compose.clearAirSnapshot("lumio_unlock_wrong_tries") {
        ClearAirGateLook.Unlock(unlock(refused = true, triesLeft = 2))
    }

    @Test
    fun unlockFailureDelay() = compose.clearAirSnapshot("lumio_unlock_wait") {
        ClearAirGateLook.Unlock(unlock(length = 6, waitMs = 38_000L))
    }

    @Test
    fun unavailable() = compose.clearAirSnapshot("lumio_unavailable") {
        ClearAirGateLook.Unavailable(detail = null, onClose = {})
    }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun unavailableDark() = compose.clearAirSnapshot("lumio_unavailable_dark") {
        ClearAirGateLook.Unavailable(detail = BackupExclusion.refusal("Lumio"), onClose = {})
    }

    private fun unlock(
        length: Int = 0,
        busy: Boolean = false,
        refused: Boolean = false,
        triesLeft: Int? = null,
        waitMs: Long? = null,
    ) = UnlockUi(
        length = length,
        busy = busy,
        refused = refused,
        triesLeft = triesLeft,
        biometricOff = false,
        waitMs = waitMs,
        onClose = {},
        onKey = {},
        onBackspace = {},
        onSubmit = {},
    )
}
