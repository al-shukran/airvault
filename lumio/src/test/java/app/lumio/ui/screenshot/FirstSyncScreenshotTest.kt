package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.pair.SyncRow
import app.lumio.pair.SyncState
import app.lumio.ui.pair.FirstSyncScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the first sync after a pairing: its rows at the start, with one row slow, with one failed chain, and once every row has settled. They
 * use the same theme, size and recording as [LumioScreenshotTest], and they are recorded with `:lumio:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstSyncScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firstSyncStart() = compose.lumioSnapshot("first_sync_start") {
        FirstSyncScreen(
            walletName = WALLET,
            fingerprint = FINGERPRINT,
            rows = mapOf(
                SyncRow.BITCOIN to SyncState.Working(slow = false),
                SyncRow.EVM to SyncState.Queued,
                SyncRow.SOLANA to SyncState.Queued,
                SyncRow.ALGORAND to SyncState.Queued,
                SyncRow.PRICES to SyncState.Queued,
            ),
            onOpenWallet = {},
        )
    }

    @Test
    fun firstSyncPartial() = compose.lumioSnapshot("first_sync_partial") {
        FirstSyncScreen(
            walletName = WALLET,
            fingerprint = FINGERPRINT,
            rows = mapOf(
                SyncRow.BITCOIN to SyncState.Done,
                SyncRow.EVM to SyncState.Working(slow = false),
                SyncRow.SOLANA to SyncState.Working(slow = true),
                SyncRow.ALGORAND to SyncState.Queued,
                SyncRow.PRICES to SyncState.Queued,
            ),
            onOpenWallet = {},
        )
    }

    @Test
    fun firstSyncFailed() = compose.lumioSnapshot("first_sync_failed") {
        FirstSyncScreen(
            walletName = WALLET,
            fingerprint = FINGERPRINT,
            rows = mapOf(
                SyncRow.BITCOIN to SyncState.Done,
                SyncRow.EVM to SyncState.Done,
                SyncRow.SOLANA to SyncState.Failed,
                SyncRow.ALGORAND to SyncState.Done,
                SyncRow.PRICES to SyncState.Done,
            ),
            onOpenWallet = {},
        )
    }

    @Test
    fun firstSyncDone() = compose.lumioSnapshot("first_sync_done") {
        FirstSyncScreen(
            walletName = WALLET,
            fingerprint = FINGERPRINT,
            rows = SyncRow.entries.associateWith { SyncState.Done },
            onOpenWallet = {},
        )
    }

    private companion object {
        const val WALLET = "Demo"
        const val FINGERPRINT = 0x2F1A0C3B
    }
}
