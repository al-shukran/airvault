package app.lensly.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletEyebrow

/**
 * The heading of an app's screen: the wallet's [walletName] above, so the reader knows whose accounts the screen shows, then the
 * app's [name] in the dot-matrix face with its [logo] beside it. [logo] is given the size of the mark.
 */
@Composable
fun AppTitle(
    logo: @Composable (size: Dp) -> Unit,
    name: String,
    walletName: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow(walletName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(LenslySpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            logo(40.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            DotText(text = name.uppercase(), modifier = Modifier.weight(1f), fontSize = 32.sp)
        }
    }
}
