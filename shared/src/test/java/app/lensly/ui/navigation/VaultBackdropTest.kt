package app.lensly.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.lensly.ui.theme.DayScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A vault screen sits on the Lumen backdrop. The status and navigation bars are drawn over the top and bottom edges of the window, so a
 * screen without the backdrop would show the cover's black behind them, and the dark icons of the Day palette would vanish on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultBackdropTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aDayVaultScreenPaintsTheDayBackdropAtTheTopEdge() {
        compose.setContent {
            NavHost(navController = rememberNavController(), startDestination = "vault") {
                // The body starts inside the corner, so only the backdrop can paint the corner pixel.
                vaultComposable("vault") { Box(Modifier.fillMaxSize().padding(48.dp)) }
            }
        }
        compose.waitForIdle()

        val corner = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(0, 0)
        assertEquals(DayScheme.background.toArgb(), corner)
    }
}
