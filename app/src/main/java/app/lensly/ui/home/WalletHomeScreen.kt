package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletMeta
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletWarningChip

/**
 * The wallet list, in the style of the contacts list: a dot-matrix headline, one row per wallet, and a red add
 * button. [onOpen] receives the wallet id. [onSettings] opens the Settings screen. [onLock] locks the vault and leaves
 * the hidden area. [onUpdateCatalog] opens the import of a signed registry package. [debugNotice], when set, shows a
 * warning chip, for example that the keystore is software-only.
 */
@Composable
fun WalletHomeScreen(
    wallets: List<WalletMeta>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onUpdateCatalog: () -> Unit,
    modifier: Modifier = Modifier,
    debugNotice: String? = null,
) {
    Box(modifier = modifier.fillMaxSize().background(LenslyBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = WALLET_GUTTER),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalletEyebrow("Offline vault")
                Spacer(Modifier.weight(1f))
                WalletPill(
                    text = "Settings",
                    onClick = onSettings,
                    style = WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
                Spacer(Modifier.width(8.dp))
                WalletPill(
                    text = "Lock",
                    onClick = onLock,
                    style = WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
            }
            Spacer(Modifier.height(12.dp))
            DotHeadline(text = "WALLETS")
            if (debugNotice != null) {
                Spacer(Modifier.height(12.dp))
                WalletWarningChip(text = debugNotice)
            }
            Spacer(Modifier.height(16.dp))
            if (wallets.isEmpty()) {
                EmptyList()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    // Room under the last row, so the add button never covers it.
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(wallets, key = { it.id }) { wallet ->
                        WalletRow(wallet = wallet, onClick = { onOpen(wallet.id) })
                    }
                }
            }
        }
        WalletPill(
            text = "Update catalog",
            onClick = onUpdateCatalog,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = WALLET_GUTTER, bottom = 16.dp),
            style = WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
        )
        AddWalletButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun EmptyList() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = "No wallets yet.",
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap + to create a wallet, or restore one from its 24 words.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WalletRow(wallet: WalletMeta, onClick: () -> Unit) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 16.dp),
        ) {
            Text(
                text = wallet.name.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = LenslyOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wallet.deviceGenerated) PillChip(text = "Device-generated")
                PillChip(text = "Master ${wallet.masterFingerprintHex.uppercase()}")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** The red circular add button from the contacts reference. */
@Composable
private fun AddWalletButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(LenslyAccent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Add wallet" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(28.dp),
        )
    }
}
