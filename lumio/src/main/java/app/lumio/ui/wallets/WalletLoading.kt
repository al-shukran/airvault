package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirSkeleton

/** The height of a row that a read has not filled yet. It is the height of a list row, so the rows around it keep their places when it lands. */
private val LOADING_ROW_HEIGHT = 62.dp

/**
 * What a wallet screen shows while its balances are not read yet: its top bar with the back arrow, and the line that says they are being checked. The
 * Wallets home reads them before a wallet opens, so this shows only when a screen opens without them.
 */
@Composable
internal fun WalletLoadingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        DetailTopBar(title = null, onBack = onBack)
        WalletLoadingContent()
    }
}

/**
 * The body of a page whose balances are not read yet, without a top bar: a page's top bar belongs to the shell that holds it. "Checking…" stands in for
 * the total, and no skeleton bars are drawn: each account says what its own read is doing.
 */
@Composable
internal fun WalletLoadingContent() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
        ClearAirEyebrow(text = "Total")
        ShimmerText(text = "Checking…", style = ClearAirType.rowTitle)
    }
}

/** A row that a read has not filled yet: a skeleton bar in a row's height, so the rows around it keep their places when it lands. */
@Composable
internal fun LoadingRow() {
    Box(
        modifier = Modifier.fillMaxWidth().height(LOADING_ROW_HEIGHT).padding(horizontal = ClearAirSpacing.gutter),
        contentAlignment = Alignment.CenterStart,
    ) {
        ClearAirSkeleton(modifier = Modifier.fillMaxWidth(0.5f).height(16.dp))
    }
}
