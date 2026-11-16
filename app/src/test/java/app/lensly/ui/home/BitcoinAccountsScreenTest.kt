package app.lensly.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.app.AccountProfile
import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.SINGLE_KEY_NO_BITCOIN
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Bitcoin App of a wallet: its accounts, each with its network badge, its Script Type and its first receive address. The filter by
 * Script Type starts on All, and each choice keeps only the accounts of that type. GENERATE ACCOUNTS opens the bulk screen on this App,
 * and a Single Key wallet has no such row, because it has no seed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class BitcoinAccountsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** What the App's routes keep while it is open: the calls the screen made. */
    private class Route {
        var generated = false
        var paired = false
        var scanned = false
        var edited: Int? = null
    }

    /** The Bitcoin App of one wallet, with [accounts] as its Bitcoin accounts. */
    @Composable
    private fun Bitcoin(route: Route, accounts: List<BitcoinAccountRecord>, kind: WalletKind = WalletKind.MNEMONIC) {
        BitcoinAccountsScreen(
            wallet = WalletMeta(
                id = "w1",
                name = "Seed wallet",
                createdAtMs = 0L,
                deviceGenerated = true,
                accountCount = 1,
                masterFingerprintHex = "1a2b3c4d",
                accountXpub = null,
                kind = kind,
                autoNameBase = "Seed wallet",
            ),
            accounts = accounts,
            onBack = {},
            onGenerate = { route.generated = true },
            onPair = { route.paired = true },
            onScan = { route.scanned = true },
            onEditAccount = { route.edited = it },
        )
    }

    @Test
    fun theFilterStartsOnAllAndShowsEveryAccount() {
        compose.setContent {
            LenslyTheme {
                Bitcoin(Route(), accounts = accounts)
            }
        }

        compose.onNodeWithText("ALL").assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(legacy.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(nativeMainnet.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(nativeTestnet.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(taproot.firstReceiveAddress)).assertIsDisplayed()
    }

    @Test
    fun aScriptFilterKeepsOnlyTheAccountsOfThatTypeAndShowsTheirNetwork() {
        compose.setContent {
            LenslyTheme {
                Bitcoin(Route(), accounts = accounts)
            }
        }

        compose.onNodeWithText("NATIVE SEGWIT BIP84").performClick()
        compose.onNodeWithText(groupedInFours(nativeMainnet.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(nativeTestnet.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(legacy.firstReceiveAddress)).assertDoesNotExist()
        compose.onNodeWithText(groupedInFours(taproot.firstReceiveAddress)).assertDoesNotExist()
        // Each row names its network: one account on mainnet and one on testnet4, which shows as Testnet.
        compose.onNodeWithText("MAINNET").assertIsDisplayed()
        compose.onNodeWithText("TESTNET").assertIsDisplayed()

        compose.onNodeWithText("TAPROOT BIP86").performClick()
        compose.onNodeWithText(groupedInFours(taproot.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(nativeMainnet.firstReceiveAddress)).assertDoesNotExist()
        compose.onNodeWithText(groupedInFours(nativeTestnet.firstReceiveAddress)).assertDoesNotExist()

        compose.onNodeWithText("LEGACY BIP44").performClick()
        compose.onNodeWithText(groupedInFours(legacy.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(taproot.firstReceiveAddress)).assertDoesNotExist()

        compose.onNodeWithText("ALL").performClick()
        compose.onNodeWithText(groupedInFours(legacy.firstReceiveAddress)).assertIsDisplayed()
        compose.onNodeWithText(groupedInFours(nativeMainnet.firstReceiveAddress)).assertIsDisplayed()
    }

    @Test
    fun aFilterWithNoAccountsSaysSo() {
        compose.setContent {
            LenslyTheme {
                Bitcoin(Route(), accounts = accounts)
            }
        }

        compose.onNodeWithText("NESTED SEGWIT BIP49").performClick()
        compose.onNodeWithText("No accounts of this script type.").assertIsDisplayed()
    }

    @Test
    fun generateAccountsOpensTheBulkScreenOfThisApp() {
        val route = Route()
        compose.setContent {
            LenslyTheme {
                Bitcoin(route, accounts = accounts)
            }
        }

        compose.onNodeWithText("GENERATE ACCOUNTS").performScrollTo().performClick()
        assertTrue(route.generated)
    }

    @Test
    fun theAppOptionsOfferGeneratingPairingAndSigning() {
        val route = Route()
        compose.setContent {
            LenslyTheme {
                Bitcoin(route, accounts = accounts)
            }
        }

        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Generate accounts").assertIsDisplayed()
        compose.onNodeWithText("Select accounts").assertDoesNotExist()
        compose.onNodeWithText("Pair this wallet").performClick()
        assertTrue(route.paired)

        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Scan to sign").performClick()
        assertTrue(route.scanned)
    }

    @Test
    fun aSingleKeyWalletHasNoBitcoinAccountsGenerateRowOrGenerateOption() {
        compose.setContent {
            LenslyTheme {
                Bitcoin(Route(), accounts = emptyList(), kind = WalletKind.SINGLE_KEY)
            }
        }

        compose.onNodeWithText("No Bitcoin accounts yet.").assertIsDisplayed()
        compose.onNodeWithText(SINGLE_KEY_NO_BITCOIN, substring = true).assertIsDisplayed()
        compose.onNodeWithText("GENERATE ACCOUNTS").assertDoesNotExist()
        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Generate accounts").assertDoesNotExist()
    }

    @Test
    fun aNamedAccountShowsItsNameAndLabelAndAnUnnamedOneItsAutoName() {
        val named = nativeMainnet.copy(profile = AccountProfile(name = "Cold", label = "vault"))
        compose.setContent {
            LenslyTheme {
                Bitcoin(Route(), accounts = listOf(named, taproot))
            }
        }

        compose.onNodeWithText("COLD").assertIsDisplayed()
        // The label chip is set in capitals, as every chip of the app is.
        compose.onNodeWithText("VAULT").assertIsDisplayed()
        // The taproot account has no name, so it shows the Auto-Name its wallet gives its index, as an Ethereum account does.
        compose.onNodeWithText("SEED WALLET 0").assertIsDisplayed()
    }

    @Test
    fun aPressOnAnAccountOffersItsProfileEditorForThatAccount() {
        val route = Route()
        compose.setContent {
            LenslyTheme {
                Bitcoin(route, accounts = accounts)
            }
        }

        compose.onNodeWithText(groupedInFours(taproot.firstReceiveAddress)).performClick()
        compose.onNodeWithText("Edit profile").performClick()
        // The taproot account is the fourth one the wallet made, so its place is 3.
        assertEquals(3, route.edited)
    }

    private companion object {
        val legacy = BitcoinAccountRecord(BtcNetwork.MAINNET, ScriptType.LEGACY, 0, "1LegacyTestAddress0000000000000000")
        val nativeMainnet = BitcoinAccountRecord(BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0, "bc1qnativemainnettestaddress000000000")
        val nativeTestnet = BitcoinAccountRecord(BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0, "tb1qnativetestnet4testaddress00000000")
        val taproot = BitcoinAccountRecord(BtcNetwork.MAINNET, ScriptType.TAPROOT, 0, "bc1ptaprootmainnettestaddress0000000000000000000")

        /** One account of each Script Type, and one of them on testnet4, so each filter and badge has a row to show. */
        val accounts = listOf(legacy, nativeMainnet, nativeTestnet, taproot)
    }
}
