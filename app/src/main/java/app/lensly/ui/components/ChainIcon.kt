package app.lensly.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lensly.R
import app.lensly.core.registry.NetworkEntry

/**
 * The drawable for [network]. A network with a logo shows it. A null network, a network without a logo, and a logo name
 * that is not in [chainIconDrawables] all show the Ethereum logo, which unknown chains share.
 */
internal fun chainIconRes(network: NetworkEntry?): Int =
    network?.icon?.let { chainIconDrawables[it] } ?: R.drawable.chain_ethereum

/**
 * A chain logo at [size]. It is decorative: the text beside it names the network. A null [network] shows the Ethereum
 * logo.
 */
@Composable
fun ChainIcon(network: NetworkEntry?, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(chainIconRes(network)),
        contentDescription = null,
        modifier = modifier.size(size),
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
