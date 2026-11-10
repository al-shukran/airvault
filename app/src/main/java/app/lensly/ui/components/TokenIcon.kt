package app.lensly.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lensly.R
import app.lensly.core.registry.TokenEntry

/**
 * Token logos that are too dark to see on black, so they draw on a light backdrop. Each one averages under 0.15 relative
 * luminance against black, over every fill and gradient stop of its drawable. The brand colours are not changed.
 * Every name here must be a key of [tokenIconDrawables]: ChainTokenIconsTest checks that. Re-measure when a logo changes.
 */
internal val tokenIconsNeedingBackdrop: Set<String> = setOf(
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

/**
 * The drawable for [token]. A token with a logo shows it. A null token, a token without a logo, and a logo name that is
 * not in [tokenIconDrawables] all show the unknown-token icon.
 */
internal fun tokenIconRes(token: TokenEntry?): Int =
    token?.icon?.let { tokenIconDrawables[it] } ?: R.drawable.ic_unknown_token

/**
 * A token logo at [size]. It is decorative: the text beside it names the token. A null [token] shows the unknown-token
 * icon. The native coin of a network is not a token: show [ChainIcon] for it. A logo in [tokenIconsNeedingBackdrop] sits
 * on a light backdrop.
 */
@Composable
fun TokenIcon(token: TokenEntry?, size: Dp, modifier: Modifier = Modifier) {
    val icon = token?.icon
    BackdropLogo(
        painter = painterResource(tokenIconRes(token)),
        size = size,
        backdrop = icon != null && icon in tokenIconsNeedingBackdrop,
        modifier = modifier,
    )
}
