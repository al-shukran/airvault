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
 * The goldens of the import after a pairing: its timeline and reads at the start, with one read slow, with one failed chain, and once every read has
 * settled. They use the same theme, size and recording as [LumioScreenshotTest], and they are recorded with `:lumio:recordRoborazziDebug`.
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
            accounts = ACCOUNTS,
            chains = CHAINS,
            rows = mapOf(
                SyncRow.BITCOIN to SyncState.Working(slow = false),
                SyncRow.EVM to SyncState.Queued,
                SyncRow.SOLANA to SyncState.Queued,
                SyncRow.ALGORAND to SyncState.Queued,
                SyncRow.PRICES to SyncState.Queued,
            ),
            onOpenWallet = {},
            onLock = {},
        )
    }

    @Test
    fun firstSyncPartial() = compose.lumioSnapshot("first_sync_partial") {
        FirstSyncScreen(
            walletName = WALLET,
            accounts = ACCOUNTS,
            chains = CHAINS,
            rows = mapOf(
                SyncRow.BITCOIN to SyncState.Done,
                SyncRow.EVM to SyncState.Working(slow = false),
                SyncRow.SOLANA to SyncState.Working(slow = true),
                SyncRow.ALGORAND to SyncState.Queued,
                SyncRow.PRICES to SyncState.Queued,
            ),
            onOpenWallet = {},
            onLock = {},
        )
    }

    @Test
    fun firstSyncFailed() = compose.lumioSnapshot("first_sync_failed") {
        FirstSyncScreen(
            walletName = WALLET,
            accounts = ACCOUNTS,
            chains = CHAINS,
            rows = mapOf(
                SyncRow.BITCOIN to SyncState.Done,
                SyncRow.EVM to SyncState.Done,
                SyncRow.SOLANA to SyncState.Failed,
                SyncRow.ALGORAND to SyncState.Done,
                SyncRow.PRICES to SyncState.Done,
            ),
            onOpenWallet = {},
            onLock = {},
        )
    }

    @Test
    fun firstSyncDone() = compose.lumioSnapshot("first_sync_done") {
        FirstSyncScreen(
            walletName = WALLET,
            accounts = ACCOUNTS,
            chains = CHAINS,
            rows = SyncRow.entries.associateWith { SyncState.Done },
            onOpenWallet = {},
            onLock = {},
        )
    }

    private companion object {
        const val WALLET = "Demo"
        const val ACCOUNTS = 10
        const val CHAINS = 4
    }
}
