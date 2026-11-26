package app.lumio.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.pair.PairChrome
import app.lumio.ui.pair.PairWithLensly
import app.lumio.ui.scan.CameraDeniedNote
import app.lumio.ui.scan.LumioScanFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Pair with Lensly page in each of its two chromes, the first run's step 10 and the page pushed from Wallets, Settings or Re-Pair, with
 * the ring part way round, and in its Camera Denied state (K6). Both chromes render one composable, so a change to the page shows in all of them. The camera
 * is replaced by a placeholder, as the other scan goldens do. They use the same theme, size and recording as [LumioScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairScreensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pairOnboarding() = compose.lumioSnapshot("pair_onboarding") { Page(PairChrome.Onboarding) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun pairOnboardingLight() = compose.lumioSnapshot("pair_onboarding_light") { Page(PairChrome.Onboarding) }

    @Test
    fun pairOnboardingCameraDenied() = compose.lumioSnapshot("pair_onboarding_camera_denied") { Page(PairChrome.Onboarding, denied = true) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun pairOnboardingCameraDeniedLight() = compose.lumioSnapshot("pair_onboarding_camera_denied_light") { Page(PairChrome.Onboarding, denied = true) }

    @Test
    fun pairManual() = compose.lumioSnapshot("pair_manual") { Page(manual("Pair with Lensly")) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun pairManualLight() = compose.lumioSnapshot("pair_manual_light") { Page(manual("Pair with Lensly")) }

    @Test
    fun pairManualCameraDenied() = compose.lumioSnapshot("pair_manual_camera_denied") { Page(manual("Pair with Lensly"), denied = true) }

    @Test
    fun rePair() = compose.lumioSnapshot("re_pair") { Page(manual("Re-Pair with Lensly")) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun rePairLight() = compose.lumioSnapshot("re_pair_light") { Page(manual("Re-Pair with Lensly")) }

    /** Camera Denied on a scan that reads any code, such as the address scan of Send. */
    @Test
    fun scanCameraDenied() = compose.lumioSnapshot("scan_camera_denied") {
        LumioScanFrame(
            title = "Scan Address",
            progress = null,
            statusText = "",
            complete = false,
            onCancel = {},
        ) { viewport ->
            CameraDeniedNote(onOpenSettings = {}, modifier = viewport)
        }
    }

    private fun manual(title: String) = PairChrome.Page(title = title, onBack = {}, onLock = {})

    @Composable
    private fun Page(chrome: PairChrome, denied: Boolean = false) {
        PairWithLensly(
            chrome = chrome,
            progress = 0.6f,
            status = "12 of about 20 parts · hold steady",
            complete = false,
            denied = denied,
            onOpenSettings = {},
        ) { viewport ->
            Box(modifier = viewport.background(MaterialTheme.colorScheme.surfaceContainerLow))
        }
    }
}
