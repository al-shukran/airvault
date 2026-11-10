package app.lensly.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lensly.R
import app.lensly.core.registry.NetworkEntry

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

/**
 * The drawable for [network]. A network with a logo shows it. A null network, a network without a logo, and a logo name
 * that is not in [chainIconDrawables] all show the Ethereum logo, which unknown chains share.
 */
internal fun chainIconRes(network: NetworkEntry?): Int =
    network?.icon?.let { chainIconDrawables[it] } ?: R.drawable.chain_ethereum

/**
 * A chain logo at [size]. It is decorative: the text beside it names the network. A null [network] shows the Ethereum
 * logo. A logo in [chainIconsNeedingBackdrop] sits on a light backdrop.
 */
@Composable
fun ChainIcon(network: NetworkEntry?, size: Dp, modifier: Modifier = Modifier) {
    val icon = network?.icon
    BackdropLogo(
        painter = painterResource(chainIconRes(network)),
        size = size,
        backdrop = icon != null && icon in chainIconsNeedingBackdrop,
        modifier = modifier,
    )
}

/**
 * The logo beside every EVM address and account. Addresses always show the Ethereum logo, whichever compatible chain
 * they are used on, so this is [ChainIcon] for no network.
 */
@Composable
fun AddressIcon(size: Dp, modifier: Modifier = Modifier) {
    ChainIcon(network = null, size = size, modifier = modifier)
}
