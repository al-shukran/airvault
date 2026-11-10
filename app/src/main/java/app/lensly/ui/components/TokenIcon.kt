package app.lensly.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.lensly.R
import app.lensly.core.registry.TokenEntry

/**
 * The drawable for [token]. A token with a logo shows it. A null token, a token without a logo, and a logo name that is
 * not in [tokenIconDrawables] all show the unknown-token icon.
 */
internal fun tokenIconRes(token: TokenEntry?): Int =
    token?.icon?.let { tokenIconDrawables[it] } ?: R.drawable.ic_unknown_token

/**
 * A token logo at [size]. It is decorative: the text beside it names the token. A null [token] shows the unknown-token
 * icon. The native coin of a network is not a token: show [ChainIcon] for it.
 */
@Composable
fun TokenIcon(token: TokenEntry?, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(tokenIconRes(token)),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}
