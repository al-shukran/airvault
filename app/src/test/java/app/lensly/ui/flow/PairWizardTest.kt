package app.lensly.ui.flow

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.ui.shippedRegistry
import app.lensly.ui.theme.LenslyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the pairing wizard starts: the wallet step is shown only when the owner has a choice to make. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-mdpi")
class PairWizardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun withOneWalletTheWizardSkipsTheWalletStepAndStartsAtTheApps() {
        val only = mnemonic("w1", "Seed wallet")
        compose.setContent {
            LenslyTheme {
                PairWizard(wallets = listOf(only), profiles = emptyMap(), preselected = null, onDone = {}, onLeave = {})
            }
        }

        compose.onNodeWithText("Step 2 of 4", ignoreCase = true).assertExists()
        compose.onNodeWithText("Choose the wallet you are pairing", substring = true).assertDoesNotExist()
        compose.onNodeWithText("RABBY (AIRGAP VAULT)").assertExists()
    }

    @Test
    fun withTwoWalletsTheWizardStartsWithTheWalletStep() {
        val wallets = listOf(mnemonic("w1", "Seed wallet"), mnemonic("w2", "Savings"))
        compose.setContent {
            LenslyTheme {
                PairWizard(wallets = wallets, profiles = emptyMap(), preselected = null, onDone = {}, onLeave = {})
            }
        }

        compose.onNodeWithText("Step 1 of 4", ignoreCase = true).assertExists()
        compose.onNodeWithText("SEED WALLET").assertExists()
        compose.onNodeWithText("SAVINGS").assertExists()
    }

    @Test
    fun aPreselectedSingleKeyGoesStraightToTheCannotBePairedNotice() {
        val key = mnemonic("w3", "Hardware key").copy(kind = WalletKind.SINGLE_KEY, masterFingerprintHex = null)
        compose.setContent {
            LenslyTheme {
                PairWizard(
                    wallets = listOf(key, mnemonic("w1", "Seed wallet")),
                    profiles = emptyMap(),
                    preselected = key,
                    onDone = {},
                    onLeave = {},
                )
            }
        }

        compose.onNodeWithText("Step 2 of 4", ignoreCase = true).assertExists()
        // The banner title is shown in capitals, so the match ignores case.
        compose.onNodeWithText("Cannot be paired", ignoreCase = true).assertExists()
    }

    @Test
    fun pickingAWalletShowsItsWalletEntryNoteOnTheInstructions() {
        compose.setContent {
            LenslyTheme {
                PairWizard(
                    wallets = listOf(mnemonic("w1", "Seed wallet")),
                    profiles = emptyMap(),
                    preselected = null,
                    onDone = {},
                    onLeave = {},
                    walletEntries = shippedRegistry().walletEntries(),
                )
            }
        }

        compose.onNodeWithText("WALLET ENTRY").assertDoesNotExist()
        compose.onNodeWithText("RABBY (AIRGAP VAULT)").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("WALLET ENTRY").assertExists()
        compose.onNodeWithText("Rabby").assertExists()
        compose.onNodeWithText("Known limitations: ", substring = true).assertExists()
    }

    @Test
    fun aWalletOutsideTheRegistryShowsNoWalletEntryNote() {
        compose.setContent {
            LenslyTheme {
                PairWizard(
                    wallets = listOf(mnemonic("w1", "Seed wallet")),
                    profiles = emptyMap(),
                    preselected = null,
                    onDone = {},
                    onLeave = {},
                    walletEntries = shippedRegistry().walletEntries(),
                )
            }
        }

        compose.onNodeWithText("OTHER WALLET").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("WALLET ENTRY").assertDoesNotExist()
    }

    private fun mnemonic(id: String, name: String) = WalletMeta(
        id = id,
        name = name,
        createdAtMs = 0L,
        deviceGenerated = true,
        accountCount = 2,
        masterFingerprintHex = "1a2b3c4d",
        accountXpub = null,
        kind = WalletKind.MNEMONIC,
        autoNameBase = name,
    )
}
