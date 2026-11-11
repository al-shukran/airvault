package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.DotoFamily
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.SpaceMonoFamily

private const val DISABLED_ALPHA = 0.4f
private val ERROR_HEIGHT = 24.dp

/** Visual role of a [WalletPill]. Primary is the single white call to action. */
internal enum class WalletPillStyle { Primary, Secondary, Outline, Accent }

/** Height and type scale of a [WalletPill]. */
internal enum class WalletPillSize { Compact, Regular, Large }

/**
 * Fully rounded button used across the wallet flow. The label is uppercased. A disabled pill keeps its
 * place in the layout but ignores taps.
 */
@Composable
internal fun WalletPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WalletPillStyle = WalletPillStyle.Secondary,
    size: WalletPillSize = WalletPillSize.Regular,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val background: Color = when (style) {
        WalletPillStyle.Primary -> LenslyOnSurface
        WalletPillStyle.Secondary -> colors.surfaceVariant
        WalletPillStyle.Accent -> colors.primary
        WalletPillStyle.Outline -> Color.Transparent
    }
    val labelColor: Color = if (style == WalletPillStyle.Primary) LenslyBlack else colors.onSurface
    val height: Dp = when (size) {
        WalletPillSize.Compact -> 40.dp
        WalletPillSize.Regular -> 56.dp
        WalletPillSize.Large -> 88.dp
    }
    val horizontal: Dp = when (size) {
        WalletPillSize.Compact -> 10.dp
        WalletPillSize.Regular -> 24.dp
        WalletPillSize.Large -> 32.dp
    }
    val textStyle = when (size) {
        WalletPillSize.Compact -> MaterialTheme.typography.labelSmall
        WalletPillSize.Regular -> MaterialTheme.typography.labelLarge
        WalletPillSize.Large -> MaterialTheme.typography.titleMedium
    }
    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(background, shape)
            .then(
                if (style == WalletPillStyle.Outline) Modifier.border(1.dp, colors.outline, shape)
                else Modifier,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = textStyle,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Page frame for every wallet screen: safe-drawing insets, the page gutter, and a [footer] pinned
 * under the content. With [scrollable] the content scrolls when it is taller than the space left, so
 * it still fits a 720x1600 screen. Without it the content takes the remaining height, which lets
 * children use `Modifier.weight`.
 */
@Composable
internal fun WalletScaffold(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = WALLET_GUTTER),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
        footer()
    }
}

/** Horizontal page gutter, matching the gate screens. */
internal val WALLET_GUTTER = 16.dp

/**
 * Small muted label above a headline, such as "SEED CEREMONY". A label that may be long can be cut with [maxLines] and
 * [overflow].
 */
@Composable
internal fun WalletEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Outlined warning chip in the accent red. Use it for risks that must not be missed. */
@Composable
internal fun WalletWarningChip(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .border(1.dp, LenslyAccent, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LenslyAccent,
        )
    }
}

/**
 * One line of accent error text. The slot always takes the same height, so the layout below does not
 * jump when an error appears or clears. Pass null for an empty slot.
 */
@Composable
internal fun WalletError(message: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ERROR_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        if (message != null) {
            Text(
                text = message.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = LenslyAccent,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Dot-matrix text for counters, check words and recent entries. */
@Composable
internal fun DotText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 28.sp,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(fontFamily = DotoFamily, fontSize = fontSize, letterSpacing = 2.sp),
        color = LenslyOnSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A full hash, grouped in fours, four groups to a line, so it can be compared by eye. */
@Composable
internal fun HashLines(hex: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        for (line in formatHexLines(hex)) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                color = LenslyOnSurface,
            )
        }
    }
}
