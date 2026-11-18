package app.lensly.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.lensly.ui.theme.DayScheme
import app.lensly.ui.theme.NightScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A vault screen sits on the Lumen backdrop. The status and navigation bars are drawn over the top and bottom edges of the window, so a
 * screen without the backdrop would show the cover's black behind them, and the dark icons of the Day palette would vanish on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class VaultBackdropTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aDayVaultScreenPaintsTheDayBackdropBehindItsBody() {
        var backdrop: Color? = null
        showVaultScreen { backdrop = MaterialTheme.colorScheme.background }

        assertEquals(DayScheme.background, backdrop)
    }

    @Test
    @Config(qualifiers = "night")
    fun aNightVaultScreenPaintsTheNightBackdropBehindItsBody() {
        var backdrop: Color? = null
        showVaultScreen { backdrop = MaterialTheme.colorScheme.background }

        assertEquals(NightScheme.background, backdrop)
    }

    /** Shows a vault screen whose body is a tagged box, then checks that the body sits inside the backdrop. [onBody] runs in its composition. */
    private fun showVaultScreen(onBody: @Composable () -> Unit) {
        compose.setContent {
            NavHost(navController = rememberNavController(), startDestination = "vault") {
                vaultComposable("vault") {
                    onBody()
                    Box(Modifier.fillMaxSize().testTag(BODY_TAG))
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasTestTag(BODY_TAG) and hasAnyAncestor(hasTestTag(HIDDEN_BACKDROP_TAG))).assertExists()
    }

    private companion object {
        const val BODY_TAG = "vault-body"
    }
}
