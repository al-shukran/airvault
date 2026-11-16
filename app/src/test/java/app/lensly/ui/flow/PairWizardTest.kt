package app.lensly.ui.flow

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.wallet.AccountXpub
import app.lensly.ui.shippedRegistry
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun onlyAKeystoneNexusCodeForAWalletWithoutAccountKeysNeedsAnHdWallet() {
        val seedWallet = mnemonic("w1", "Seed wallet").copy(accountKeys = listOf(AccountXpub(ByteArray(33) { 2 }, ByteArray(32), 0)))
        val key = mnemonic("w3", "Hardware key").copy(kind = WalletKind.SINGLE_KEY, masterFingerprintHex = null)

        assertTrue(keystoneNeedsHdWallet(PairFormat.KEYSTONE_NEXUS, key))
        assertFalse(keystoneNeedsHdWallet(PairFormat.KEYSTONE_NEXUS, seedWallet))
        assertFalse(keystoneNeedsHdWallet(PairFormat.RABBY, key))
        assertFalse(keystoneNeedsHdWallet(PairFormat.KEYSTONE_NEXUS, null))
    }

    @Test
    fun keystoneNexusForASingleKeyShowsTheHdWalletNoticeInsteadOfWaitingForACode() {
        val key = mnemonic("w3", "Hardware key").copy(kind = WalletKind.SINGLE_KEY, masterFingerprintHex = null)
        compose.setContent {
            LenslyTheme {
                PairWizardScreen(
                    step = PairStep.CODE,
                    wallets = listOf(key),
                    profiles = emptyMap(),
                    wallet = key,
                    format = PairFormat.KEYSTONE_NEXUS,
                    frames = null,
                    keystoneNeedsHd = keystoneNeedsHdWallet(PairFormat.KEYSTONE_NEXUS, key),
                    onPickWallet = {},
                    onPickFormat = {},
                    onShowCode = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Needs an HD wallet", ignoreCase = true).assertExists()
        compose.onNodeWithText("Preparing code").assertDoesNotExist()
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

        // The Lumio option is not in the registry, so its steps show no note.
        compose.onNodeWithText("LUMIO (AIRVAULT COMPANION)").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("WALLET ENTRY").assertDoesNotExist()
    }

    @Test
    fun lumioIsTheFirstOnlineWalletAndTheRecommendedOne() {
        assertEquals(PairFormat.LUMIO, PairFormat.entries.first())
        assertEquals(listOf(PairFormat.LUMIO), PairFormat.entries.filter { it.recommended })

        compose.setContent {
            LenslyTheme {
                PairWizard(
                    wallets = listOf(mnemonic("w1", "Seed wallet")),
                    profiles = emptyMap(),
                    preselected = null,
                    onDone = {},
                    onLeave = {},
                )
            }
        }

        compose.onNodeWithText("Step 2 of 4", ignoreCase = true).assertExists()
        compose.onNodeWithText("LUMIO (AIRVAULT COMPANION)").assertExists()
        compose.onNodeWithText("Recommended", ignoreCase = true).assertExists()
    }

    @Test
    fun theLumioCodeIsOneCodeWithNoEthereumOrBitcoinChoice() {
        val wallet = mnemonic("w1", "Seed wallet")
        compose.setContent {
            LenslyTheme {
                PairWizardScreen(
                    step = PairStep.CODE,
                    wallets = listOf(wallet),
                    profiles = emptyMap(),
                    wallet = wallet,
                    format = PairFormat.LUMIO,
                    frames = null,
                    onPickWallet = {},
                    onPickFormat = {},
                    onShowCode = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }

        // The one code covers every App of the wallet, so no App is offered on its own. Its frames are still being prepared.
        compose.onNodeWithText("Ethereum", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Bitcoin", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Preparing code", ignoreCase = true).assertExists()
    }

    private fun mnemonic(id: String, name: String) = WalletMeta(
        id = id,
        name = name,
        createdAtMs = 0L,
        deviceGenerated = true,
        accountCount = 2,
        masterFingerprintHex = "1a2b3c4d",
        accountKeys = emptyList(),
        kind = WalletKind.MNEMONIC,
        autoNameBase = name,
    )
}
