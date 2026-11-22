package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.firstrun.DeviceProtectionScreen
import app.lumio.ui.firstrun.DuressScreen
import app.lumio.ui.firstrun.FirstRunPasswordScreen
import app.lumio.ui.firstrun.KeysScreen
import app.lumio.ui.firstrun.NeverShareScreen
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.ui.firstrun.NoticeScreen
import app.lumio.ui.firstrun.PrepareScreen
import app.lumio.ui.firstrun.ProvidedAsIsScreen
import app.lumio.ui.firstrun.WelcomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of Lumio's first-run screens I1 to I9, in the dark theme, with the light theme for a few. The password screen is shown empty
 * and with a mismatch. They use the same theme, size and recording as [LumioScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstRunScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcome() = compose.lumioSnapshot("first_run_welcome") { WelcomeScreen(onNext = {}) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun welcomeLight() = compose.lumioSnapshot("first_run_welcome_light") { WelcomeScreen(onNext = {}) }

    @Test
    fun keys() = compose.lumioSnapshot("first_run_keys") { KeysScreen(onNext = {}) }

    @Test
    fun neverShare() = compose.lumioSnapshot("first_run_never_share") { NeverShareScreen(onNext = {}) }

    @Test
    fun providedAsIs() = compose.lumioSnapshot("first_run_provided_as_is") { ProvidedAsIsScreen(onNext = {}) }

    @Test
    fun notice() = compose.lumioSnapshot("first_run_notice") { NoticeScreen(agreed = false, onAgreedChange = {}, onContinue = {}) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun noticeAgreedLight() = compose.lumioSnapshot("first_run_notice_agreed_light") {
        NoticeScreen(agreed = true, onAgreedChange = {}, onContinue = {})
    }

    @Test
    fun prepareLensly() = compose.lumioSnapshot("first_run_prepare_lensly") { PrepareScreen(onNext = {}) }

    @Test
    fun createPassword() = compose.lumioSnapshot("first_run_create_password") {
        PasswordScreen(password = "", repeat = "", problem = null)
    }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun createPasswordMismatchLight() = compose.lumioSnapshot("first_run_create_password_mismatch_light") {
        PasswordScreen(password = "Gh5%Rt8!Yu2#Pk6@Ws3Fn", repeat = "Gh5%Rt8!Yu2#Pk6@Ws3Fn!", problem = NewPasswordProblem.Mismatch)
    }

    @Test
    fun duress() = compose.lumioSnapshot("first_run_duress") { DuressScreen(onMaybeLater = {}) }

    @Test
    fun deviceProtection() = compose.lumioSnapshot("first_run_device_protection") {
        DeviceProtectionScreen(available = true, onEnable = {}, onNotNow = {})
    }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun deviceProtectionLight() = compose.lumioSnapshot("first_run_device_protection_light") {
        DeviceProtectionScreen(available = true, onEnable = {}, onNotNow = {})
    }

    @Test
    fun deviceProtectionUnavailable() = compose.lumioSnapshot("first_run_device_protection_unavailable") {
        DeviceProtectionScreen(available = false, onEnable = {}, onNotNow = {})
    }

    @Composable
    private fun PasswordScreen(password: String, repeat: String, problem: NewPasswordProblem?) {
        FirstRunPasswordScreen(
            password = password,
            repeat = repeat,
            shown = false,
            problem = problem,
            busy = false,
            onPassword = {},
            onRepeat = {},
            onToggleShown = {},
            onNext = {},
        )
    }
}
