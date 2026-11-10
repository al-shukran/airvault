package app.lensly.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle

/** How many networks and tokens the catalog holds: the built-in entries plus any installed package. */
data class CatalogCounts(val networks: Int, val tokens: Int)

/**
 * The Settings screen of the vault, a LazyColumn of sections in the Nothing style of the wallet screens: Entry, Bulk
 * generation, Catalog and About. LOCK stays in the header.
 *
 * [bulkCap] is null until the saved setting has been read, and its options are off until then. [onBulkCap] saves the
 * new cap. [onChangePassword] opens the App Password change. [catalog] is null while the counts are read. The hidden
 * entry gesture is read-only, because the detector has one fixed gesture.
 */
@Composable
fun SettingsScreen(
    bulkCap: BulkCap?,
    onBulkCap: (BulkCap) -> Unit,
    onChangePassword: () -> Unit,
    catalog: CatalogCounts?,
    onUpdateCatalog: () -> Unit,
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = WALLET_GUTTER),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SettingsHeader(onBack = onBack, onLock = onLock) }

            item { SectionTitle("Entry") }
            item {
                ReadOnlyBlock(
                    title = "Hidden entry gesture",
                    body = "Two-finger double-tap on the viewfinder, then hold both fingers.",
                    note = "This version has one gesture. Choosing another is not available yet.",
                )
            }
            item {
                ActionBlock(
                    title = "Change App Password",
                    body = "Enter the current password, then the new one twice. Your wallets stay as they are.",
                    onClick = onChangePassword,
                )
            }

            item { SectionTitle("Bulk generation") }
            item { BulkCapBlock(bulkCap = bulkCap, onBulkCap = onBulkCap) }

            item { SectionTitle("Catalog") }
            item {
                ActionBlock(
                    title = "Update catalog",
                    body = "Install a signed package from removable storage. It adds networks and tokens.",
                    onClick = onUpdateCatalog,
                )
            }
            item { CatalogSummaryBlock(catalog = catalog) }

            item { SectionTitle("About") }
            item { ValueBlock(label = "Version", value = "$versionName ($versionCode)") }
            item { ValueBlock(label = "License", value = "Apache License 2.0. The text is in the LICENSE file.") }
            item { NoticesBlock() }
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit, onLock: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletPill(
                text = "Back",
                onClick = onBack,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
            Spacer(Modifier.weight(1f))
            WalletPill(
                text = "Lock",
                onClick = onLock,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        Spacer(Modifier.height(20.dp))
        WalletEyebrow("Offline vault")
        Spacer(Modifier.height(8.dp))
        DotHeadline(text = "SETTINGS")
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
        WalletEyebrow(text)
        Spacer(Modifier.height(4.dp))
    }
}

/** A row with a title and a note, and nothing to tap. */
@Composable
private fun ReadOnlyBlock(title: String, body: String, note: String? = null) {
    Column {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
            Spacer(Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodyLarge, color = LenslyOnSurface)
            if (note != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** A row that opens another screen. The whole row and its OPEN pill both act. */
@Composable
private fun ActionBlock(title: String, body: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            WalletPill(
                text = "Open",
                onClick = onClick,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** The Bulk Cap: one pill per cap the owner may select, the saved one filled. Chunked runs are not offered yet. */
@Composable
private fun BulkCapBlock(bulkCap: BulkCap?, onBulkCap: (BulkCap) -> Unit) {
    Column {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text(text = "Bulk Cap", style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The most accounts one bulk run can create. A run above it is refused.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cap in BulkCap.entries) {
                    WalletPill(
                        text = cap.label,
                        onClick = { onBulkCap(cap) },
                        style = if (cap == bulkCap) WalletPillStyle.Secondary else WalletPillStyle.Outline,
                        size = WalletPillSize.Compact,
                        enabled = bulkCap != null,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Chunked runs of 100 at a time are not available yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun CatalogSummaryBlock(catalog: CatalogCounts?) {
    Column {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            WalletEyebrow("Networks and tokens")
            Spacer(Modifier.height(6.dp))
            Text(
                text = catalog?.let { "${it.networks} networks · ${it.tokens} tokens" } ?: "Reading the catalog…",
                style = MaterialTheme.typography.bodyLarge,
                color = LenslyOnSurface,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ValueBlock(label: String, value: String) {
    Column {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            WalletEyebrow(label)
            Spacer(Modifier.height(6.dp))
            Text(text = value, style = MaterialTheme.typography.bodyLarge, color = LenslyOnSurface)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun NoticesBlock() {
    Column {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            WalletEyebrow("Third-party notices")
            Spacer(Modifier.height(6.dp))
            NoticeLine("Chain and token logos: web3icons (MIT) and Iconify token-icons (MIT).")
            NoticeLine("Logo attribution files are in tools/icons/.")
            NoticeLine("Fonts: Doto and Space Mono, SIL Open Font License 1.1. The texts are in licenses/.")
            NoticeLine(
                "Libraries: Bouncy Castle (Bouncy Castle Licence), zxcvbn (MIT License), and web3j, ZXing and " +
                    "Hummingbird (Apache License 2.0).",
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun NoticeLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
