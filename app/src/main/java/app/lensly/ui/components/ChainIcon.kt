package app.lensly.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.R
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.theme.DotoFamily

/**
 * Chain logos that are too dark to see on black, so they draw on a light backdrop. Each one averages under 0.15 relative
 * luminance against black, over every fill and gradient stop of its drawable. The brand colours are not changed.
 * Every name here must be a key of [chainIconDrawables]: ChainTokenIconsTest checks that. Re-measure when a logo changes.
 */
internal val chainIconsNeedingBackdrop: Set<String> = setOf(
    "chain_abstract",
    "chain_apechain",
    "chain_base",
    "chain_cronos",
    "chain_cronos_zkevm",
    "chain_gnosis",
    "chain_lisk",
    "chain_mega_eth",
    "chain_polygon",
    "chain_polygon_zkevm",
    "chain_sei_network",
)

/** What [ChainIcon] draws for a network. [chainIconSpec] decides it, without Compose, so the rules can be unit tested. */
internal sealed interface ChainIconSpec {
    /** A logo drawable. With [backdrop] set, the logo sits on a light disc. */
    data class Logo(@DrawableRes val res: Int, val backdrop: Boolean) : ChainIconSpec

    /** A network with no logo of its own: its [letter] in a thin outline circle. */
    data class Monogram(val letter: String) : ChainIconSpec
}

/**
 * What to draw for [network]. A null network is not in the registry, so it shows the Ethereum logo, which unknown chains
 * share. A network in the registry shows its own logo. A registry network with no logo, or with a logo name that is not
 * in [chainIconDrawables], shows a monogram of its name. It never shows the Ethereum logo for a network of its own.
 */
internal fun chainIconSpec(network: NetworkEntry?): ChainIconSpec {
    if (network == null) return ChainIconSpec.Logo(R.drawable.chain_ethereum, backdrop = false)
    val icon = network.icon
    val res = icon?.let { chainIconDrawables[it] }
    return if (icon != null && res != null) {
        ChainIconSpec.Logo(res, backdrop = icon in chainIconsNeedingBackdrop)
    } else {
        ChainIconSpec.Monogram(monogramOf(network.name))
    }
}

/** The letter of a monogram: the first letter or digit of [name] in capitals, or "?" when the name has neither. */
internal fun monogramOf(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/**
 * A chain logo at [size]. It is decorative: the text beside it names the network. A null [network] shows the Ethereum
 * logo. A registry network with no logo shows a monogram of its name, in the same [size]. A logo in
 * [chainIconsNeedingBackdrop] sits on a light backdrop.
 */
@Composable
fun ChainIcon(network: NetworkEntry?, size: Dp, modifier: Modifier = Modifier) {
    when (val spec = chainIconSpec(network)) {
        is ChainIconSpec.Logo -> BackdropLogo(
            painter = painterResource(spec.res),
            size = size,
            backdrop = spec.backdrop,
            modifier = modifier,
        )
        is ChainIconSpec.Monogram -> Monogram(letter = spec.letter, size = size, modifier = modifier)
    }
}

/** The width of the monogram's outline circle. */
private val monogramRing: Dp = 1.dp

/** The letter's font size as a share of the icon size. */
private const val MONOGRAM_LETTER_SHARE = 0.6f

/**
 * A network with no logo: its letter in the dot-matrix face, inside a thin circle in the muted foreground colour. It takes
 * the same [size] as a logo, so it lines up with the other icons.
 */
@Composable
private fun Monogram(letter: String, size: Dp, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    val letterSize = (size.value * MONOGRAM_LETTER_SHARE).sp
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                val stroke = monogramRing.toPx()
                drawCircle(
                    color = ring,
                    radius = (this.size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = TextStyle(
                fontFamily = DotoFamily,
                fontSize = letterSize,
                lineHeight = letterSize,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The logo beside every EVM address and account. Addresses always show the Ethereum logo, whichever compatible chain
 * they are used on, so this is [ChainIcon] for no network.
 */
@Composable
fun AddressIcon(size: Dp, modifier: Modifier = Modifier) {
    ChainIcon(network = null, size = size, modifier = modifier)
}
