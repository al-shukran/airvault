package app.lumio.ui.theme

import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Reduced motion follows the system's animator duration scale: zero is the owner's "remove animations" choice. */
@RunWith(RobolectricTestRunner::class)
class ReducedMotionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reducedMotionIsOnWhenTheAnimatorScaleIsZero() {
        setAnimatorScale(0f)
        showReducedMotion()

        compose.onNodeWithText("reduced: true").assertExists()
    }

    @Test
    fun reducedMotionIsOffAtTheDefaultScale() {
        setAnimatorScale(1f)
        showReducedMotion()

        compose.onNodeWithText("reduced: false").assertExists()
    }

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private fun showReducedMotion() {
        compose.setContent {
            LumioTheme {
                Text("reduced: ${LocalReducedMotion.current}")
            }
        }
    }
}
