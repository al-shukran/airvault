package app.lensly.ui.components

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
import app.lensly.core.registry.NetworkEntry
import app.lensly.shared.R

/**
 * Chain logos too dark for a dark page, which sit on a light disc there. Each averages under 0.15 relative luminance against black, over
 * every fill and gradient stop of its drawable. The brand colours are not changed. Every name here must be a key of [chainIconDrawables]:
 * ChainTokenIconsTest checks that. Re-measure when a logo changes.
 */
val chainIconsNeedingLightBackdrop: Set<String> = setOf(
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

/**
 * Chain logos too pale for a light page, which sit on a dark disc there. Measured on the emulator, the average colour of each logo's artwork
 * is within 1.25:1 contrast of the light page, so the logo vanishes on it. The next logo up measured 1.44:1. The brand colours are not
 * changed. Every name here must be a key of [chainIconDrawables]: ChainTokenIconsTest checks that. Re-measure when a logo changes.
 */
val chainIconsNeedingDarkBackdrop: Set<String> = setOf(
    "chain_blast",
    "chain_celo",
    "chain_fraxtal",
    "chain_fuse",
    "chain_immutable",
    "chain_kaia",
    "chain_katana",
    "chain_lens",
    "chain_linea",
    "chain_mantle",
    "chain_mode",
    "chain_okex",
    "chain_plasma",
    "chain_soneium",
    "chain_sonic",
    "chain_world",
    "chain_x_layer",
    "chain_zksync",
)

/**
 * The drawable of the logo [network] shows, or null when it has none, and then [ChainIcon] shows a monogram of its name. A null network is not
 * in the registry, so it shows the Ethereum logo, which unknown chains share. Otherwise the logo is the drawable that the network's icon names.
 * A name that is not in [chainIconDrawables] is a logo this build does not ship, so the network has none. A network never shows the Ethereum
 * logo for itself.
 */
internal fun chainLogo(network: NetworkEntry?): Int? {
    if (network == null) return R.drawable.chain_ethereum
    val icon = network.icon ?: return null
    return chainIconDrawables[icon]
}

/**
 * The drawable of the logo a record shows for the network it names by [name], looked up in [networks] (see [chainLogo]). A name the registry
 * does not list has no logo: [NetworkLogo] shows a letter for it, never the Ethereum logo.
 */
internal fun networkLogoOf(name: String?, networks: List<NetworkEntry>): Int? {
    val network = networks.firstOrNull { it.name == name } ?: return null
    return chainLogo(network)
}

/** The letter of a monogram: the first letter or digit of [name] in capitals, or "?" when the name has neither. */
internal fun monogramOf(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/**
 * A chain logo at [size]. It is decorative: the text beside it names the network. A null [network] shows the Ethereum
 * logo. A registry network with no logo shows a monogram of its name, in the same [size]. A logo that would vanish on the
 * page sits on a disc (see [BackdropLogo]).
 */
@Composable
fun ChainIcon(network: NetworkEntry?, size: Dp, modifier: Modifier = Modifier) {
    val logo = chainLogo(network)
    when {
        logo != null -> BackdropLogo(
            painter = painterResource(logo),
            size = size,
            tone = logoTone(logo),
            modifier = modifier,
        )
        network != null -> LetterMonogram(letter = monogramOf(network.name), size = size, modifier = modifier)
    }
}

/**
 * The logo a record shows for the network it names by [name], looked up in [networks] (see [networkLogoOf]). A network with no logo, and a name no
 * network has, show a letter of the name instead, and a record that names no network shows "?". It is decorative: the text beside it names the network.
 */
@Composable
fun NetworkLogo(name: String?, networks: List<NetworkEntry>, size: Dp, modifier: Modifier = Modifier) {
    val logo = networkLogoOf(name, networks)
    if (logo != null) {
        BackdropLogo(painter = painterResource(logo), size = size, tone = logoTone(logo), modifier = modifier)
    } else {
        LetterMonogram(letter = monogramOf(name.orEmpty()), size = size, modifier = modifier)
    }
}

/** The width of the monogram's outline circle. */
private val monogramRing: Dp = 1.dp

/** The letter's font size as a share of the icon size. */
private const val MONOGRAM_LETTER_SHARE = 0.6f

/**
 * A letter in the dot-matrix face, inside a thin circle in the muted foreground colour. It marks a network with no logo, and
 * a token with no logo: the neutral letter avatar. It takes the same [size] as a logo, so it lines up with the other icons.
 */
@Composable
fun LetterMonogram(letter: String, size: Dp, modifier: Modifier = Modifier) {
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
                fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
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

/** The Bitcoin logo beside a Bitcoin account. It is decorative: the text beside it names the account. */
@Composable
fun BitcoinIcon(size: Dp, modifier: Modifier = Modifier) {
    BackdropLogo(painter = painterResource(R.drawable.chain_bitcoin), size = size, tone = logoTone(R.drawable.chain_bitcoin), modifier = modifier)
}

/** The Solana logo beside a Solana account. It is decorative: the text beside it names the account. */
@Composable
fun SolanaIcon(size: Dp, modifier: Modifier = Modifier) {
    BackdropLogo(painter = painterResource(R.drawable.ic_solana), size = size, backdrop = false, modifier = modifier)
}

/** The Algorand logo beside an Algorand account. It is decorative: the text beside it names the account. */
@Composable
fun AlgorandIcon(size: Dp, modifier: Modifier = Modifier) {
    BackdropLogo(painter = painterResource(R.drawable.ic_algorand), size = size, backdrop = true, modifier = modifier)
}
