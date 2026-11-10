package app.lensly.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/** Placeholder for the pair and sign screens, which are not built yet. */
@Composable
fun SoonScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            WalletPill(
                text = "Back",
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                style = WalletPillStyle.Outline,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        DotHeadline(text = "SOON")
    }
}
