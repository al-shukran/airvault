package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/** Height of the line that reports a wrong word. It is kept even when there is no error, so the choices do not move. */
private val ERROR_HEIGHT = 72.dp

/**
 * Quiz that checks the owner wrote the words down. It asks for one word by position and offers four
 * shuffled choices. A wrong pick shows an error, and the caller restarts the quiz (see [MnemonicQuiz]).
 */
@Composable
fun MnemonicConfirmScreen(
    state: ConfirmState,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Check words",
        modifier = modifier,
        onBack = onBack,
    ) {
        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Check ${state.step + 1} of ${state.total}")
        Spacer(Modifier.height(LenslySpacing.xs))
        ProgressTrack(fraction = state.step.toFloat() / state.total)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "WORD #${state.position}")
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "Pick the word at this position.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Box(
            modifier = Modifier.fillMaxWidth().height(ERROR_HEIGHT),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (state.failed) {
                InlineBanner(message = "Wrong word. The quiz starts again.", tone = BannerTone.Error)
            }
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        state.choices.forEach { choice ->
            PillButton(
                text = choice,
                onClick = { onPick(choice) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LenslySpacing.sm))
        }
    }
}
