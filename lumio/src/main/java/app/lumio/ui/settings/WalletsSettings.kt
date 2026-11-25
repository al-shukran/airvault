package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.DebugDemoData
import app.lumio.pair.PairedWallet
import app.lumio.ui.kit.ClearAirDialog
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirGlyphDisc
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirWalletEmblem
import app.lumio.ui.wallets.ROW_DIVIDER_INDENT
import app.lumio.ui.wallets.RowDivider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The test tag of the action that pairs a wallet from Lensly. */
const val PAIR_WALLET_TAG = "pair-wallet"

/** The test tag of the confirm button of a deletion. */
const val DELETE_CONFIRM_TAG = "delete-confirm"

/** The test tag of the row that removes a wallet. */
const val DELETE_WALLET_TAG = "delete-wallet"

/** The test tag of a wallet's row in the Wallets list, by the wallet's ID. */
fun walletRowTag(walletId: String): String = "wallet-$walletId"

/** The number of accounts a wallet holds, on every chain family. */
private fun accountCount(wallet: PairedWallet): Int = wallet.evmAccounts.size + wallet.bitcoin.size + wallet.solana.size + wallet.algorand.size

private fun plural(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"

/** A wallet's line in a list: its account count and the day it was paired, such as "30 accounts · paired 21 Nov". */
fun walletSummary(wallet: PairedWallet): String {
    val paired = SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(wallet.createdAtMs))
    return "${plural(accountCount(wallet), "account")} · paired $paired"
}

/**
 * The Wallets page: a row for each paired wallet, with its emblem, its account count and the day it was paired, and ACTIVE beside the wallet [activeId] of
 * the side that Home shows. A row opens the wallet's settings with [onOpen]. "Pair Another Wallet" pairs one more from Lensly, and the caption says that
 * names are made on Lensly.
 */
@Composable
fun WalletsSettings(wallets: List<PairedWallet>, activeId: String?, onOpen: (PairedWallet) -> Unit, onPair: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        wallets.forEachIndexed { index, wallet ->
            if (index > 0) RowDivider(indent = ROW_DIVIDER_INDENT)
            ClearAirRow(
                title = wallet.name,
                description = walletSummary(wallet),
                leading = { ClearAirWalletEmblem() },
                tag = if (wallet.id == activeId) "ACTIVE" else null,
                onClick = { onOpen(wallet) },
                modifier = Modifier.testTag(walletRowTag(wallet.id)),
            )
        }
        ClearAirTextAction(
            text = if (wallets.isEmpty()) "Pair a Wallet" else "Pair Another Wallet",
            onClick = onPair,
            icon = ClearAirGlyph.PLUS,
            modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = ClearAirSpacing.xs).testTag(PAIR_WALLET_TAG),
        )
        Text(
            text = if (wallets.isEmpty()) "No wallets paired yet. Pair one from Lensly." else "Wallet and account names come from Lensly. Rename them there, then re-pair.",
            modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
            style = ClearAirType.body,
            color = palette.ink2,
        )
    }
}

/**
 * The settings of one paired wallet: its emblem and name, which Lensly gives, when it was paired, how many accounts and Address Book entries it holds,
 * then "Re-Pair with Lensly" and "Remove from Lumio". A removal asks first, and it erases only Lumio's watch-only copy. The wallet in Lensly and its
 * keys are not changed.
 */
@Composable
fun WalletSettingsPage(wallet: PairedWallet, offline: String?, onRePair: () -> Unit, onRemove: () -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    var removing by remember { mutableStateOf(DebugDemoData.flag("wallet:remove")) }
    SettingsFrame(title = wallet.name, offline = offline, onBack = onBack, onLock = onLock, backLabel = SettingsPage.WALLETS.title) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirWalletEmblem(tile = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(text = wallet.name, style = ClearAirType.titleL, color = palette.ink)
                Text(text = "Named on Lensly · can't be edited here", style = ClearAirType.body, color = palette.ink2)
            }
        }
        Column(modifier = Modifier.padding(top = 14.dp)) {
            SettingsFact(label = "Paired", value = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(wallet.createdAtMs)))
            val apps = wallet.families.size
            SettingsFact(label = "Accounts", value = "${accountCount(wallet)} in ${plural(apps, "Lensly app")}")
            val entries = wallet.addressBook.size
            SettingsFact(label = "Address Book", value = "$entries ${if (entries == 1) "entry" else "entries"}", last = true)
        }
        ClearAirRow(
            title = "Re-Pair with Lensly",
            description = "Adds new accounts, names and address-book entries. Keeps your watchlist and settings.",
            leading = { ClearAirGlyphDisc(ClearAirGlyph.REFRESH, size = 19.dp) },
            onClick = onRePair,
            modifier = Modifier.padding(top = ClearAirSpacing.sm, bottom = ClearAirSpacing.xxs),
        )
        RowDivider(indent = ROW_DIVIDER_INDENT)
        ClearAirRow(
            title = "Remove from Lumio",
            description = "Lensly and your keys are not affected",
            leading = { ClearAirGlyphDisc(ClearAirGlyph.TRASH, color = LocalClearAirPalette.current.rose, size = 19.dp) },
            destructive = true,
            modifier = Modifier.padding(top = ClearAirSpacing.xxs).testTag(DELETE_WALLET_TAG).lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = { removing = true }),
        )
    }
    if (removing) {
        ClearAirDialog(
            title = "Remove ${wallet.name} from Lumio?",
            text = "Lensly and your keys are not affected. You can pair ${wallet.name} again at any time.",
            onDismiss = { removing = false },
        ) {
            ClearAirPrimaryButton(
                text = "Remove ${wallet.name}",
                onClick = {
                    removing = false
                    onRemove()
                },
                destructive = true,
                modifier = Modifier.testTag(DELETE_CONFIRM_TAG),
            )
            ClearAirTextAction(text = "Cancel", onClick = { removing = false })
        }
    }
}
