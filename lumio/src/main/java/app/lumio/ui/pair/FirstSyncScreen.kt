package app.lumio.ui.pair

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.pair.FirstSync
import app.lumio.pair.SyncRow
import app.lumio.pair.SyncState
import app.lumio.ui.kit.ClearAirPageTopBar
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.rememberLumioHaptics

/**
 * The import after a pairing is saved (I12): a timeline of three lines, the accounts that the pairing brought and the chains they are on, and a note
 * for each read that runs long or fails, which never blocks the page. The last line closes once every read has settled, with a haptic. Nothing opens
 * the wallet by itself. This is the last step of the onboarding: Continue is the primary action and the only control that leaves it. View Accounts
 * opens the wallet's accounts above this page, and the back arrow and the system back stay here.
 */
@Composable
fun FirstSyncScreen(
    walletName: String,
    accounts: Int,
    chains: Int,
    rows: Map<SyncRow, SyncState>,
    onViewAccounts: () -> Unit,
    onFinish: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val settled = FirstSync.isSettled(rows)
    val haptics = rememberLumioHaptics()
    LaunchedEffect(settled) {
        if (settled) haptics.success()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // The import is the last step of the onboarding, so its back arrow stays on it.
        ClearAirPageTopBar(title = "Setting Up $walletName", backLabel = "Pair with Lensly", onBack = {}, onLock = onLock)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
                Spacer(Modifier.height(ClearAirSpacing.md))
                TimelineLine(text = "Finding Accounts", step = Step.DONE)
                TimelineLine(text = "$accounts Accounts Imported Across $chains blockchains", step = Step.DONE)
                TimelineLine(text = if (settled) "Importing Complete" else "Importing", step = if (settled) Step.DONE else Step.LIVE)
                if (settled) {
                    Spacer(Modifier.height(ClearAirSpacing.md))
                    Text(text = "Your $accounts accounts have been imported successfully.", style = ClearAirType.body, color = palette.ink)
                }
                rows.forEach { (row, state) ->
                    description(row, state)?.let { note ->
                        Spacer(Modifier.height(ClearAirSpacing.xs))
                        Text(
                            text = note,
                            style = ClearAirType.label,
                            color = if (state == SyncState.Failed) palette.rose else palette.ink2,
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.md)) {
            ClearAirRow(title = "View Accounts", onClick = onViewAccounts)
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirPrimaryButton(
                text = "Continue",
                onClick = onFinish,
                modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
            )
        }
    }
}

/** Where a timeline line is: done, with a tick in ink, or live, with the spark that marks the step that needs the owner's wait. */
private enum class Step { DONE, LIVE }

/** One line of the timeline: a disc with its tick or its spark, and the line's words beside it. */
@Composable
private fun TimelineLine(text: String, step: Step) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(20.dp).clip(CircleShape).background(if (step == Step.DONE) palette.primary else palette.spark),
            contentAlignment = Alignment.Center,
        ) {
            if (step == Step.DONE) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = palette.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Text(text = text, style = ClearAirType.rowTitle, color = if (step == Step.DONE) palette.ink else palette.ink2)
    }
}

/** The name of a row, as the sync reads it. */
private fun SyncRow.title(): String = when (this) {
    SyncRow.BITCOIN -> "Bitcoin"
    SyncRow.EVM -> "EVM Networks"
    SyncRow.SOLANA -> "Solana"
    SyncRow.ALGORAND -> "Algorand"
    SyncRow.PRICES -> "Prices"
}

/** What a row says under the timeline: a read that runs long, or a read that failed. A queued or finished row says nothing. */
private fun description(row: SyncRow, state: SyncState): String? = when (state) {
    is SyncState.Working -> if (state.slow) "Still working. ${row.title()} is slow." else null
    SyncState.Failed -> "Couldn't reach ${row.title()}. Lumio will retry."
    SyncState.Queued, SyncState.Done -> null
}
