package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline

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
    WalletScaffold(modifier = modifier) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletPill(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.widthIn(min = 96.dp),
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
            Spacer(Modifier.weight(1f))
            WalletEyebrow("Check ${state.step + 1} of ${state.total}")
        }
        Spacer(Modifier.height(24.dp))
        DotHeadline(text = "WORD #${state.position}")
        Spacer(Modifier.height(16.dp))
        WalletError(message = if (state.failed) "Wrong word. Quiz restarted." else null)
        Spacer(Modifier.height(16.dp))
        state.choices.forEach { choice ->
            WalletPill(
                text = choice,
                onClick = { onPick(choice) },
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Secondary,
                size = WalletPillSize.Large,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
