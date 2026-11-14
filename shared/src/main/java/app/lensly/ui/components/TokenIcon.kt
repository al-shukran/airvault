package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lensly.shared.R
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.theme.LenslyBlack

/**
 * Token logos that are too dark to see on black, so they draw on a light backdrop. Each one averages under 0.15 relative
 * luminance against black, over every fill and gradient stop of its drawable. The brand colours are not changed.
 * Every name here must be a key of [tokenIconDrawables]: ChainTokenIconsTest checks that. Re-measure when a logo changes.
 */
val tokenIconsNeedingBackdrop: Set<String> = setOf(
    "token_bal",
    "token_cbbtc",
    "token_cbeth",
    "token_fet",
    "token_link",
    "token_matic",
    "token_pol",
    "token_snx",
    "token_tbtc",
    "token_tusd",
)

/** The name of a Binance-Peg token on BNB Chain, such as "Binance Pegged USDT". Written by tools/registry-data. */
private const val BINANCE_PEGGED_PREFIX = "Binance Pegged "
private const val BNB_CHAIN_ID = 56L

/** The suffix of an Avalanche Bridge token, such as "AAVE.e". Written by tools/registry-data. */
private const val AVALANCHE_BRIDGED_SUFFIX = ".e"
private const val AVALANCHE_CHAIN_ID = 43114L

/** Copies of ether have no logo of their own, so they show the Ethereum logo, which is the ether logo. */
private val etherSymbols: Set<String> = setOf("ETH", "WETH")

/** The badge's width and height as a share of the token logo. */
private const val BADGE_SHARE = 0.5f

/** The chain logo's width and height as a share of the badge, which leaves a black ring around it. */
private const val BADGE_LOGO_SHARE = 0.8f

/**
 * The symbol of the asset a pegged or bridged copy stands for, or null for any other token. The registry names copies
 * rather than marking them. A Binance-Peg token is on BNB Chain and named "Binance Pegged ..." (its symbol is the
 * asset's).
 * An Avalanche Bridge token is on Avalanche with the ".e" suffix, and the symbol is the asset's without it.
 */
internal fun copiedSymbolOf(token: TokenEntry): String? = when {
    token.chainId == BNB_CHAIN_ID && token.name.startsWith(BINANCE_PEGGED_PREFIX) -> token.symbol
    token.chainId == AVALANCHE_CHAIN_ID && token.symbol.endsWith(AVALANCHE_BRIDGED_SUFFIX) ->
        token.symbol.removeSuffix(AVALANCHE_BRIDGED_SUFFIX)
    else -> null
}

/**
 * The drawable for [token]. A token with a logo shows it. A copy of ether with no logo shows the Ethereum logo. A null
 * token, any other token without a logo, and a logo name that is not in [tokenIconDrawables] all show the unknown-token
 * icon.
 */
internal fun tokenIconRes(token: TokenEntry?): Int {
    token?.icon?.let { tokenIconDrawables[it] }?.let { return it }
    val copied = token?.let(::copiedSymbolOf)
    return if (copied != null && copied in etherSymbols) R.drawable.chain_ethereum else R.drawable.ic_unknown_token
}

/**
 * The chain logo drawn as a badge on [token], or null. Only a pegged or bridged copy has one. The badge is the logo of the
 * chain the copy is on, which is the chain that issued it. [chain] must be that chain. A null [chain] (not in the
 * registry), a chain without a logo, and any other token have no badge.
 */
internal fun tokenBadgeSpec(token: TokenEntry?, chain: NetworkEntry?): ChainIconSpec.Logo? {
    if (token == null || chain == null || chain.chainId != token.chainId) return null
    if (copiedSymbolOf(token) == null) return null
    return chainIconSpec(chain) as? ChainIconSpec.Logo
}

/**
 * A token logo at [size]. It is decorative: the text beside it names the token. A null [token] shows the unknown-token
 * icon. The native coin of a network is not a token: show [ChainIcon] for it. A logo in [tokenIconsNeedingBackdrop]
 * sits on a light backdrop. A pegged or bridged copy shows the logo of its own [chain] as a badge at the bottom right.
 */
@Composable
fun TokenIcon(token: TokenEntry?, size: Dp, modifier: Modifier = Modifier, chain: NetworkEntry? = null) {
    val icon = token?.icon
    val backdrop = icon != null && icon in tokenIconsNeedingBackdrop
    val badge = tokenBadgeSpec(token, chain)
    if (badge == null) {
        BackdropLogo(
            painter = painterResource(tokenIconRes(token)),
            size = size,
            backdrop = backdrop,
            modifier = modifier,
        )
        return
    }
    Box(modifier = modifier.size(size)) {
        BackdropLogo(
            painter = painterResource(tokenIconRes(token)),
            size = size,
            backdrop = backdrop,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(size * BADGE_SHARE)
                .clip(CircleShape)
                .background(LenslyBlack),
            contentAlignment = Alignment.Center,
        ) {
            BackdropLogo(
                painter = painterResource(badge.res),
                size = size * BADGE_SHARE * BADGE_LOGO_SHARE,
                backdrop = badge.backdrop,
            )
        }
    }
}
