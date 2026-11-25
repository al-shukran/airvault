package app.lumio.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.home.Masking
import app.lumio.portfolio.PortfolioFormat

/** The change line under a hero or a page total: JetBrains Mono Medium, 13 sp, a little tighter than a row value. */
val ClearAirChangeLineStyle: TextStyle
    get() = ClearAirType.rowValue.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.02).em)

/** A change over 24 hours in percent with its arrow, such as "▼ 1.15%": rose when it fell, vault blue when it rose, and ink2 when it did not move. */
@Composable
fun ClearAirChangePercent(percent: Double, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Text(
        text = PortfolioFormat.change(percent),
        modifier = modifier,
        style = ClearAirType.body,
        color = when {
            percent < 0 -> palette.rose
            percent > 0 -> palette.vaultBlue
            else -> palette.ink2
        },
    )
}

/**
 * A change over 24 hours with its amount in dollars and the word "Today", such as "▼ 1.15% · −$1,728.13 Today". It is rose when the dollars fell, and
 * vault blue otherwise. Nothing shows unless both figures are known. A hidden [shown] masks the dollars, and the percent stays.
 */
@Composable
fun ClearAirChangeLine(changePct: Double?, changeUsd: Double?, shown: Boolean, modifier: Modifier = Modifier) {
    if (changePct == null || changeUsd == null) return
    val palette = LocalClearAirPalette.current
    val dollars = Masking.amount(PortfolioFormat.signedAmount(changeUsd, LocalFiat.current), hidden = !shown)
    Text(
        text = "${PortfolioFormat.change(changePct)} · $dollars Today",
        modifier = modifier,
        style = ClearAirChangeLineStyle,
        color = if (changeUsd < 0) palette.rose else palette.vaultBlue,
    )
}
