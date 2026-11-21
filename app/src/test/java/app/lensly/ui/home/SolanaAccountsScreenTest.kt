package app.lensly.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Solana App of a wallet: a refused single add shows its reason under the list, where the Add Account action was. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class SolanaAccountsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRefusedAddShowsItsReason() {
        val reason = "A wallet keeps at most 100 Solana accounts."
        compose.setContent {
            LumenTheme {
                SolanaAccountsScreen(
                    wallet = WalletMeta(
                        id = "w1",
                        name = "Seed wallet",
                        createdAtMs = 0L,
                        deviceGenerated = true,
                        accountCount = 1,
                        masterFingerprintHex = "1a2b3c4d",
                        accountKeys = emptyList(),
                        kind = WalletKind.MNEMONIC,
                        autoNameBase = "Seed wallet",
                    ),
                    addresses = listOf("So11111111111111111111111111111111111111112"),
                    onBack = {},
                    onAddAccount = {},
                    onBulkGenerate = {},
                    onPair = {},
                    onScan = {},
                    refusal = reason,
                )
            }
        }
        compose.onNodeWithText(reason).assertIsDisplayed()
    }
}
