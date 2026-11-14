package app.lumio.ui.home

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.LenslyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lumio's Home before any wallet is paired: the empty state, a disabled pairing action, and a back arrow that locks. */
@RunWith(RobolectricTestRunner::class)
class LumioHomeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saysThatNoWalletIsPairedYet() {
        setHome(onLock = {})

        compose.onNodeWithText("No wallets paired yet", ignoreCase = true).assertExists()
    }

    @Test
    fun theWalletPairingActionIsShownButDisabled() {
        setHome(onLock = {})

        compose.onNodeWithText("Pair from Lensly", ignoreCase = true).assertIsNotEnabled()
    }

    @Test
    fun theBackArrowLocksAndLeavesTheHome() {
        var locked = false
        setHome(onLock = { locked = true })

        compose.onNodeWithContentDescription("Back").performClick()

        assertTrue(locked)
    }

    private fun setHome(onLock: () -> Unit) {
        compose.setContent {
            LenslyTheme {
                LumioHome(onLock = onLock)
            }
        }
    }
}
