package app.lumio.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon

/**
 * A row of an ordered list of providers: its [number] in a ring, the provider's [title], and under it the status word in the colour of its state, with a
 * plain [detail] after it. A row with a [dragHandle] shows the handle at its end, and one without it shows a chevron when [onClick] opens it. The text
 * opens the row, and the handle starts a drag, so the two do not meet.
 */
@Composable
internal fun ProviderOrderRow(
    number: Int,
    title: String,
    status: ProviderStatus,
    detail: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    dragHandle: Modifier? = null,
) {
    val palette = LocalClearAirPalette.current
    val wordColor = when (status) {
        ProviderStatus.Ready, is ProviderStatus.Healthy -> palette.vaultBlue
        is ProviderStatus.CircuitOpen, is ProviderStatus.CoolingDown -> palette.sparkText
        ProviderStatus.Off, ProviderStatus.NotActive, ProviderStatus.NeedsKey -> palette.ink2
    }
    val line = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = ClearAirType.rowTitle.fontFamily, fontWeight = FontWeight.SemiBold, color = wordColor)) {
            append(providerStatusWord(status))
        }
        if (detail != null) append(" · $detail")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 62.dp)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp).border(1.5.dp, palette.ink3, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                text = number.toString(),
                style = ClearAirType.time.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                color = palette.ink2,
            )
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null) {
                        Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(text = title, style = ClearAirType.rowTitle, color = palette.ink)
            Text(text = line, style = ClearAirType.caption, color = palette.ink2)
        }
        if (dragHandle != null) {
            Spacer(Modifier.width(ClearAirSpacing.xs))
            Box(modifier = dragHandle.size(ClearAirSpacing.touchTarget), contentAlignment = Alignment.Center) {
                ClearAirIcon(glyph = ClearAirGlyph.DRAG, color = palette.ink3, size = 20.dp)
            }
        } else if (onClick != null) {
            Spacer(Modifier.width(ClearAirSpacing.xs))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3, size = 16.dp)
        }
    }
}

/** The line after a provider's status word: what the owner can do about it, when it is retrying, or whether Lumio asks it now. [inUse] is the first working provider of its group. */
internal fun providerStatusDetail(status: ProviderStatus, inUse: Boolean): String = when (status) {
    ProviderStatus.NeedsKey -> "needs an API key"
    ProviderStatus.Off -> "switched off"
    ProviderStatus.NotActive -> "not in use"
    is ProviderStatus.CircuitOpen -> "retrying in ${seconds(status.retryInMs)} s"
    is ProviderStatus.CoolingDown -> "retrying in ${seconds(status.inMs)} s"
    ProviderStatus.Ready, is ProviderStatus.Healthy -> if (inUse) "in use now" else "next in line"
}

/** Whole seconds in [ms], rounded up, so a wait never reads 0 s while it runs. */
private fun seconds(ms: Long): Long = (ms + 999) / 1_000

/** Whether [status] is a provider that answers: working, or ready for its first call. */
internal fun ProviderStatus.working(): Boolean = this == ProviderStatus.Ready || this is ProviderStatus.Healthy
