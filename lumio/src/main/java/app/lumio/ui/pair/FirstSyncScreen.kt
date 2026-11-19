package app.lumio.ui.pair

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.LumioRow
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalLumenPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion
import app.lumio.pair.FirstSync
import app.lumio.pair.SyncRow
import app.lumio.pair.SyncState
import app.lumio.ui.kit.GlowBackdrop
import app.lumio.ui.kit.LumenRing
import app.lumio.ui.kit.WalletOrb
import app.lumio.ui.kit.rememberLumioHaptics
import kotlinx.coroutines.delay


/** The pause after the last row settles, before the wallet opens (section 2.1). */
private const val SETTLED_PAUSE_MS = 600L

/** The height of the glow behind the orb, and the orb's diameter: the hero size of a wallet (section 1.5). */
private val GLOW_HEIGHT = 160.dp
private val ORB_SIZE = 72.dp

/** The arc of the Lumen ring that spins beside a row which is reading. */
private const val READING_PROGRESS = 0.75f

/**
 * The first sync after a pairing is saved (section 2.1): the wallet's orb in its glow, and one row per read. Each row is queued, reading, done or
 * failed, and a failed row says so without stopping the others. When every row has settled, the sync confirms with a haptic, waits a moment and
 * calls [onOpenWallet]. The back arrow and the text action open the wallet at once.
 */
@Composable
fun FirstSyncScreen(
    walletName: String,
    fingerprint: Int,
    rows: Map<SyncRow, SyncState>,
    onOpenWallet: () -> Unit,
) {
    val settled = FirstSync.isSettled(rows)
    val haptics = rememberLumioHaptics()
    val openWallet by rememberUpdatedState(onOpenWallet)
    LaunchedEffect(settled) {
        if (settled) {
            haptics.success()
            delay(SETTLED_PAUSE_MS)
            openWallet()
        }
    }
    LenslyScreen(
        title = "Setting Up $walletName",
        onBack = onOpenWallet,
        bottomAction = {
            LumioTextAction(text = "Open Wallet Now", onClick = onOpenWallet, modifier = Modifier.align(Alignment.CenterHorizontally))
        },
    ) {
        GlowBackdrop(modifier = Modifier.fillMaxWidth().height(GLOW_HEIGHT), breathing = !settled) {
            WalletOrb(fingerprint = fingerprint, diameter = ORB_SIZE, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(LenslySpacing.md))
        LumioIsland {
            rows.forEach { (row, state) ->
                LumioRow(title = row.title(), description = description(row, state), trailing = { Trailing(state) })
            }
        }
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

/** What a row says under its name: a read that runs long, or a read that failed. A queued or finished row says nothing. */
private fun description(row: SyncRow, state: SyncState): String? = when (state) {
    is SyncState.Working -> if (state.slow) "Still working. ${row.title()} is slow." else null
    SyncState.Failed -> "Couldn't reach ${row.title()}. Lumio will retry."
    SyncState.Queued, SyncState.Done -> null
}

/** The four things a row's end can show. */
private enum class Shown { QUEUED, READING, DONE, FAILED }

/**
 * The end of a row: a dim word while it is queued, the Lumen ring turning beside "Reading" (only the word with reduced motion), a check when it is
 * done, or a negative dot when it failed. Each change fades and pops in on the springs of section 3.1.
 */
@Composable
private fun Trailing(state: SyncState) {
    val shown = when (state) {
        SyncState.Queued -> Shown.QUEUED
        is SyncState.Working -> Shown.READING
        SyncState.Done -> Shown.DONE
        SyncState.Failed -> Shown.FAILED
    }
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            (fadeIn(LumenMotion.effects) + scaleIn(LumenMotion.bouncy, initialScale = 0.8f)).togetherWith(fadeOut(LumenMotion.effects))
        },
        label = "sync row state",
    ) { kind ->
        when (kind) {
            Shown.QUEUED -> Label("Queued")
            Shown.READING -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (!LocalReducedMotion.current) {
                    LumenRing(progress = READING_PROGRESS, spinning = true, size = 16.dp)
                    Spacer(Modifier.width(LenslySpacing.xs))
                }
                Label("Reading")
            }
            Shown.DONE -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Done",
                tint = LocalLumenPalette.current.positive,
                modifier = Modifier.size(20.dp),
            )
            Shown.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                Spacer(Modifier.width(LenslySpacing.xs))
                Label("Failed", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Label(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
}
