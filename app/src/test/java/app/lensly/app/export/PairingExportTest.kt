package app.lensly.app.export

import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

/**
 * The public 12-word test mnemonic "abandon x11 about". A 12-word phrase is not a [app.lensly.core.wallet.Mnemonic], so its seed comes
 * from web3j, as in HdWalletTest.
 */
private val WALLET by lazy {
    HdWallet(MnemonicUtils.generateSeed(List(11) { "abandon" }.plus("about").joinToString(" "), ""))
}

/**
 * The Ledger Live addresses m/44'/60'/N'/0/0 of "abandon x11 about" for N = 0 until 10, from an independent implementation:
 * ethers 6.13.4, `HDNodeWallet.fromPhrase(phrase, undefined, "m/44'/60'/N'/0/0").address`. Only N = 0 equals the BIP44 address
 * m/44'/60'/0'/0/N, which is why an export of the single key m/44'/60'/0' matched Lensly for the first account only (#822).
 */
private val LEDGER_LIVE = listOf(
    "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
    "0x78839F6054d7ed13918bAe0473BA31b1Ca9D7265",
    "0x07B5FdfEB4E11826D233403Fe8Db0611CCF4c231",
    "0x8559A4270Db933caC30830f2be9D099fD477A51D",
    "0xB9506F020318c2ECA294e0557222511f1e5333CB",
    "0xE465D679e57f918e9c5357122a51687984e8B550",
    "0x4a809C7D06985824f2Cc05741A8D4f8427f3ba26",
    "0x8BAE832C68d7c90C11bCA5EB8Bd0f14d49bd9e8F",
    "0x1bf8DDB62D4302fb457639Fb68617C77FfAd2554",
    "0xbd360E2157d41C9b42e9735720A46D7C4573Af6A",
)

class PairingExportTest {

    private fun keys(count: Int) = List(count) { WALLET.accountXpub(it) }

    @Test
    fun theImporterListsLenslysAccountsInOrderOnTheirLedgerLivePaths() {
        for (count in listOf(5, 10)) {
            val imported = importedAccounts(PairingExport.urParts(keys(count), WALLET.masterFingerprint, "Savings"))
            assertEquals(LEDGER_LIVE.take(count), imported.map { it.address }, "$count accounts")
            assertEquals(List(count) { "m/44'/60'/$it'/0/0" }, imported.map { it.path })
        }
    }

    @Test
    fun theImporterListsTheAddressesLenslyShows() {
        val keys = keys(PairingCodec.MAX_ETHEREUM_ACCOUNTS)
        val imported = importedAccounts(PairingExport.urParts(keys, WALLET.masterFingerprint, "Savings"))
        assertEquals(keys.map { AccountAddresses.addressAt(it, 0) }, imported.map { it.address })
    }

    @Test
    fun theCodeCarriesTheMasterFingerprintAndTheWalletName() {
        val imported = importedWallet(PairingExport.urParts(keys(5), WALLET.masterFingerprint, "Savings"))
        // 73c5da0a is the published BIP32 master fingerprint of "abandon x11 about". Rabby stamps it on every request it sends.
        assertEquals(0x73c5da0a, imported.masterFingerprint)
        assertEquals(List(5) { "Savings" }, imported.accounts.map { it.name })
    }

    @Test
    fun anExportIsWholePagesOfFive() {
        for (count in listOf(0, 1, 4, 6)) {
            assertFailsWith<IllegalArgumentException>("$count accounts") { PairingExport.urParts(keys(count), WALLET.masterFingerprint, "L") }
        }
        assertEquals(listOf(5, 5, 5, 10, 100), listOf(1, 4, 5, 6, 100).map(PairingExport::exportedCount))
    }
}
