package app.lensly.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.ErrorState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.LoadingState
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PrimaryCircleButton
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Feedback and state components, and the screen frame, rendered once each for visual review. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun feedbackStates() = compose.snapshot("feedback_states") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.lg),
        ) {
            LoadingState(label = "Loading wallets")
            EmptyState(
                icon = Icons.Filled.Info,
                title = "No wallets yet",
                body = "Create or restore a wallet to see it here.",
                actionLabel = "Create wallet",
            )
            ErrorState(
                icon = Icons.Filled.Close,
                title = "Could not load",
                body = "Try again in a moment.",
                actionLabel = "Retry",
            )
        }
    }

    @Test
    fun inlineBanners() = compose.snapshot("inline_banners") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            InlineBanner(message = "Nothing leaves this device.", tone = BannerTone.Info, title = "Note")
            InlineBanner(message = "Write the words down before you continue.", tone = BannerTone.Warning, title = "Warning")
            InlineBanner(message = "The passwords did not match.", tone = BannerTone.Error, title = "Error")
        }
    }

    @Test
    fun screenFrameWithButtons() = compose.snapshot("screen_frame_with_buttons") {
        LenslyScreen(
            title = "Settings",
            onBack = {},
            bottomAction = {
                PillButton(text = "Continue", onClick = {}, accent = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(LenslySpacing.xs))
                PillButton(text = "Disabled", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
            },
        ) {
            Text(
                text = "Body copy sits here, in the sans face.",
                style = MaterialTheme.typography.bodyLarge,
                color = LenslyOnSurface,
            )
            Spacer(Modifier.height(LenslySpacing.lg))
            PrimaryCircleButton(icon = Icons.Filled.Info, onClick = {}, contentDescription = "Enabled")
            Spacer(Modifier.height(LenslySpacing.md))
            PrimaryCircleButton(
                icon = Icons.Filled.Info,
                onClick = {},
                contentDescription = "Disabled",
                enabled = false,
            )
        }
    }
}
