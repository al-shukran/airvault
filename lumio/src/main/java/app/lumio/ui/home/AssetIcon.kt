package app.lumio.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.components.AlgorandIcon
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.LetterMonogram
import app.lensly.ui.components.SolanaIcon
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.components.tokenIconDrawables
import app.lumio.home.HomeAsset
import app.lumio.home.TokenAsset
import app.lumio.ui.kit.ClearAirDisc

/** What Home draws beside one asset. [assetIconSpec] decides it without Compose, so the mapping is unit tested. */
sealed interface AssetIconSpec {
    /** The Bitcoin logo, for every Bitcoin coin. */
    data object Bitcoin : AssetIconSpec

    /** The Solana logo, for the Solana coin. */
    data object Solana : AssetIconSpec

    /** The Algorand logo, for the Algorand coin. */
    data object Algorand : AssetIconSpec

    /** The chain logo of a native coin. A null [network] is not in the registry, and shows the Ethereum logo, as [ChainIcon] does. */
    data class Chain(val network: NetworkEntry?) : AssetIconSpec

    /** The token's own logo from the registry. [chain] is the network it is on, which a pegged or bridged copy shows as its badge. */
    data class Token(val token: TokenEntry, val chain: NetworkEntry?) : AssetIconSpec

    /** A token with no logo: a neutral letter, the first letter or digit of its symbol. A token never shows a chain logo. */
    data class Letter(val letter: String) : AssetIconSpec
}

/**
 * The icon of [asset]. [network] is the registry entry of its EVM chain, and [token] the registry entry of an ERC-20 token, or null when the
 * registry does not list it. A token shows its own logo only when the registry names a logo that Lensly ships. Any other token, the owner's
 * or one with no logo, gets a letter, so no token row ever shows a chain logo.
 */
internal fun assetIconSpec(asset: HomeAsset, network: NetworkEntry?, token: TokenEntry?): AssetIconSpec = when {
    asset.network.isBtc -> AssetIconSpec.Bitcoin
    asset !is TokenAsset -> AssetIconSpec.Chain(network)
    token != null && hasLogo(token) -> AssetIconSpec.Token(token, network)
    else -> AssetIconSpec.Letter(letterOf(asset.symbol))
}

/** True when the registry names a logo for [token] and Lensly ships it. */
private fun hasLogo(token: TokenEntry): Boolean = token.icon?.let { tokenIconDrawables.containsKey(it) } == true

/** The icon of an ERC-20 row: the token's logo when Lensly ships it, else a letter, so a token never shows a chain logo. */
internal fun tokenIcon(token: TokenEntry?, network: NetworkEntry?, symbol: String): AssetIconSpec =
    if (token != null && hasLogo(token)) AssetIconSpec.Token(token, network) else AssetIconSpec.Letter(letterOf(symbol))

/** The letter of a token avatar: the first letter or digit of [symbol] in capitals, or "?" when the symbol has neither. */
internal fun letterOf(symbol: String): String =
    symbol.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** The logo of the EVM network [chainId], or the letter of its [name] when the registry does not list it, so a custom network never shows the Ethereum logo. */
internal fun chainIconOf(registry: Registry, chainId: Long, name: String): AssetIconSpec =
    registry.network(chainId)?.let { AssetIconSpec.Chain(it) } ?: AssetIconSpec.Letter(letterOf(name))

/** The icon of [spec] at [size]. It is decorative: the text beside it names the asset. */
@Composable
internal fun AssetIcon(spec: AssetIconSpec, size: Dp, modifier: Modifier = Modifier) {
    when (spec) {
        AssetIconSpec.Bitcoin -> BitcoinIcon(size = size, modifier = modifier)
        AssetIconSpec.Solana -> SolanaIcon(size = size, modifier = modifier)
        AssetIconSpec.Algorand -> AlgorandIcon(size = size, modifier = modifier)
        is AssetIconSpec.Chain -> ChainIcon(network = spec.network, size = size, modifier = modifier)
        is AssetIconSpec.Token -> TokenIcon(token = spec.token, size = size, modifier = modifier, chain = spec.chain)
        is AssetIconSpec.Letter -> LetterMonogram(letter = spec.letter, size = size, modifier = modifier)
    }
}

/** The icon of [spec] on the 36 dp disc that leads a list row. */
@Composable
internal fun AssetDisc(spec: AssetIconSpec, modifier: Modifier = Modifier) {
    ClearAirDisc(modifier) { AssetIcon(spec = spec, size = 21.dp) }
}
