package app.lensly.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import app.lensly.core.btc.BtcFeeThresholds
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.NumericKeypad
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/** Which of the two fee limits a screen shows and edits. */
enum class FeeWarningBound { RATE, SHARE }

/**
 * The fee limits of a Bitcoin review: the fee rate and the fee share above which the review shows a WARN notice. A pill chooses the
 * limit the keypad edits. [rate] and [share] are the digits typed so far. [error] says why SAVE refused the limits, and SAVE stays off
 * until [ready], which is when the saved limits have been read.
 */
@Composable
fun FeeWarningScreen(
    rate: String,
    share: String,
    focus: FeeWarningBound,
    error: String?,
    ready: Boolean,
    onFocus: (FeeWarningBound) -> Unit,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Fee warnings",
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            PillButton(text = "Save", onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = ready)
        },
    ) {
        Text(
            text = "A Bitcoin review shows a warning when its fee rate is above the first limit, or when its fee is above the second " +
                "share of the amount the request sends to others. A warning does not stop signing. The rate runs from " +
                "${BtcFeeThresholds.MIN_RATE_SAT_PER_VB} to ${BtcFeeThresholds.MAX_RATE_SAT_PER_VB} sat/vB, and the share from " +
                "${BtcFeeThresholds.MIN_SHARE_PERCENT} to ${BtcFeeThresholds.MAX_SHARE_PERCENT} per cent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            PillButton(
                text = "Rate ${rate.ifEmpty { "\u2014" }} sat/vB",
                onClick = { onFocus(FeeWarningBound.RATE) },
                modifier = Modifier.weight(1f).semantics { selected = focus == FeeWarningBound.RATE },
                accent = focus == FeeWarningBound.RATE,
                enabled = ready,
            )
            PillButton(
                text = "Share ${share.ifEmpty { "\u2014" }}%",
                onClick = { onFocus(FeeWarningBound.SHARE) },
                modifier = Modifier.weight(1f).semantics { selected = focus == FeeWarningBound.SHARE },
                accent = focus == FeeWarningBound.SHARE,
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
