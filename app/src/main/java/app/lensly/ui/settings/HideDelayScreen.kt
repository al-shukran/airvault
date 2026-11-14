package app.lensly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.lensly.app.HideDelay
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.NumericKeypad
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/** Which of the two hide delays a screen shows and edits. */
enum class HideDelayBound { SHORTEST, LONGEST }

/**
 * The hide delay of the dice and coin tiles, shortest and longest, in milliseconds. A pill chooses the bound the keypad
 * edits. [shortest] and [longest] are the digits typed so far, empty when a bound has none. [error] says why SAVE refused
 * the range, and SAVE stays off until [ready], which is when the saved delay has been read.
 */
@Composable
fun HideDelayScreen(
    shortest: String,
    longest: String,
    focus: HideDelayBound,
    error: String?,
    ready: Boolean,
    onFocus: (HideDelayBound) -> Unit,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Hidden tiles",
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            PillButton(text = "Save", onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = ready)
        },
    ) {
        Text(
            text = "The dice and coin tiles stay hidden for a random time after a tap, drawn evenly from the shortest to the " +
                "longest. Each is from 0 to ${HideDelay.LIMIT_MS} ms, and the shortest cannot be longer than the longest.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            PillButton(
                text = "Min ${shortest.ifEmpty { "—" }} ms",
                onClick = { onFocus(HideDelayBound.SHORTEST) },
                modifier = Modifier.weight(1f).semantics { selected = focus == HideDelayBound.SHORTEST },
                accent = focus == HideDelayBound.SHORTEST,
                enabled = ready,
            )
            PillButton(
                text = "Max ${longest.ifEmpty { "—" }} ms",
                onClick = { onFocus(HideDelayBound.LONGEST) },
                modifier = Modifier.weight(1f).semantics { selected = focus == HideDelayBound.LONGEST },
                accent = focus == HideDelayBound.LONGEST,
                enabled = ready,
            )
        }
        error?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error, modifier = Modifier.padding(bottom = LenslySpacing.xs))
        }
        Spacer(Modifier.height(LenslySpacing.md))
        NumericKeypad(onDigit = onDigit, onBackspace = onBackspace, enabled = ready)
    }
}
