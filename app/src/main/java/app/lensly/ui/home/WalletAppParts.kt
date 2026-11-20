package app.lensly.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletApp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.AlgorandIcon
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.SolanaIcon
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletScaffold

/** How far a pressed account row shrinks: the same 0.98 as the other rows of an island. */
private const val ACCOUNT_PRESS_SCALE = 0.98f

/** How many accounts an App holds, in words a reader takes in at a glance: "1 account", "3 accounts". */
internal fun accountCountLabel(count: Int): String = if (count == 1) "1 account" else "$count accounts"

/** The logo of an App: the Bitcoin logo, or the Ethereum logo that every EVM address shows. It is decorative beside the App's name. */
@Composable
internal fun AppIcon(app: WalletApp, size: Dp, modifier: Modifier = Modifier) {
    when (app) {
        WalletApp.BITCOIN -> BitcoinIcon(size = size, modifier = modifier)
        WalletApp.ETHEREUM -> AddressIcon(size = size, modifier = modifier)
        WalletApp.SOLANA -> SolanaIcon(size = size, modifier = modifier)
        WalletApp.ALGORAND -> AlgorandIcon(size = size, modifier = modifier)
    }
}

/**
 * The head of an App's accounts, in the wallet detail's faces: the App's logo beside the wallet's name, and under the name how many accounts
 * the App holds. The top bar already names the App, so the head does not repeat it.
 */
@Composable
internal fun AppHeader(app: WalletApp, walletName: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LenslySpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app = app, size = 56.dp)
        Spacer(Modifier.width(LenslySpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = walletName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
            Text(
                text = accountCountLabel(count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The frame of an App's accounts, in the wallet detail's pattern: the top bar with the App's options, the App's head, then the "Accounts"
 * heading with [trailing] at its end and [beforeAccounts] under it, and the accounts in one island. The island scrolls on its own, so a
 * wallet with hundreds of accounts composes only the rows on screen. [footer] sits under the island: the one text action, and the notes.
 */
@Composable
internal fun AppAccountsScaffold(
    title: String,
    app: WalletApp,
    walletName: String,
    count: Int,
    onBack: () -> Unit,
    onOptions: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    beforeAccounts: @Composable () -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    accounts: LazyListScope.() -> Unit,
) {
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = {
            DetailTopBar(title = title, onBack = onBack, onOptions = onOptions, optionsLabel = "App options")
        },
        footer = footer,
    ) {
        AppHeader(app = app, walletName = walletName, count = count)
        Spacer(Modifier.height(LenslySpacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Accounts",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            trailing()
        }
        beforeAccounts()
        Spacer(Modifier.height(LenslySpacing.xs))
        LumioIsland(modifier = Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), content = accounts)
        }
    }
}

/**
 * One account in an island: an optional [leading] mark, then the [content] in a column. A hairline runs above the row unless [divider] is
 * false, which it is for the island's first row. A tap calls [onClick] and a long press [onLongClick]; a row without [onClick] takes no press.
 */
@Composable
internal fun AccountRow(
    divider: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (divider) IslandDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.lumioPress(scale = ACCOUNT_PRESS_SCALE, onClick = onClick, onLongClick = onLongClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(LenslySpacing.sm))
            }
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}

/**
 * The options of one App: its generate and select actions, then pairing and signing, which act on the whole wallet. The App's logo
 * and name head the sheet. A null action is not offered: [onGenerate] is null for a Single Key, and [onSelect] is null where there
 * is no list of accounts to select from.
 */
@Composable
internal fun AppOptionsSheet(
    app: WalletApp,
    onDismiss: () -> Unit,
    onGenerate: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    onPair: () -> Unit,
    onScan: () -> Unit,
) {
    OptionsSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app = app, size = 40.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(bottom = LenslySpacing.xs),
        )
        if (onGenerate != null) {
            SheetOption(icon = Icons.Filled.Add, label = "Generate Accounts", onClick = onGenerate)
        }
        if (onSelect != null) {
            SheetOption(icon = Icons.Filled.Check, label = "Select Accounts", onClick = onSelect)
        }
        SheetOption(icon = LenslyIcons.QrCode, label = "Pair This Wallet", onClick = onPair)
        SheetOption(icon = LenslyIcons.QrScan, label = "Scan to Sign", onClick = onScan)
    }
}
