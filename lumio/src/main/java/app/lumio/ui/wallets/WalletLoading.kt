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
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.theme.LenslySpacing
import app.lumio.ui.kit.Skeleton
import app.lumio.ui.theme.LumioGutter

/** The height of a row of an island. A row that a read has not filled yet takes the same height, so its island keeps its size. */
private val LOADING_ROW_HEIGHT = 64.dp

/**
 * What a wallet screen shows while its balances are not read yet: its top bar with the back arrow, and skeleton bars where the balances will be. The
 * Wallets home reads them before a wallet opens, so this shows only when a screen opens without them.
 */
@Composable
internal fun WalletLoadingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        DetailTopBar(title = null, onBack = onBack)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = LumioGutter, vertical = LenslySpacing.md)) {
            Skeleton(modifier = Modifier.fillMaxWidth(0.5f).height(LenslySpacing.xl))
            Column(modifier = Modifier.padding(top = LenslySpacing.lg)) {
                LumioIsland {
                    LoadingRow()
                    LoadingRow()
                }
            }
        }
    }
}

/** A row that a read has not filled yet: a skeleton bar in a row's height, so the rows around it keep their places when it lands. */
@Composable
internal fun LoadingRow() {
    Box(
        modifier = Modifier.fillMaxWidth().height(LOADING_ROW_HEIGHT).padding(horizontal = LenslySpacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        Skeleton(modifier = Modifier.fillMaxWidth(0.5f).height(16.dp))
    }
}
