package app.lumio.pair

import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The accounts of the paired wallets on every chain, each named as the pairing named it, with the Auto-Name for an account that has no name. */
class OwnAccountsTest {
    private val savings = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val trading = "0x52908400098527886E0F7030069857D2E4169EE7"

    private fun wallet(name: String, vararg accounts: PairedAccount) = PairedWallet(
        id = name,
        name = name,
        fingerprint = 0x5436d724L,
        createdAtMs = 1L,
        bitcoin = emptyList(),
        evmAccounts = accounts.toList(),
    )

    @Test
    fun anEthereumAccountIsNamedByThePairingNameAndLabel() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", PairedAccount(0, savings, "Savings", "Cold", null))))

        assertEquals(InternalAccount(displayName = "Savings", autoName = "Vault 0", label = "Cold"), accounts[addressKeyOf(savings)])
    }

    @Test
    fun aRenamedWalletNamesAnAccountWithNoNameFromItsAutoNameBase() {
        val renamed = wallet("New", PairedAccount(1, trading, null, null, null)).copy(autoNameBase = "Old")

        assertEquals("Old 1", ownAccountsOf(listOf(renamed))[addressKeyOf(trading)]?.autoName)
    }

    @Test
    fun anEthereumAddressIsFoundInAnyLetterCase() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", PairedAccount(0, savings, "Savings", null, null))))

        assertEquals("Savings", accounts[addressKeyOf(savings.lowercase())]?.displayName)
        assertEquals("Savings", accounts[addressKeyOf(savings.uppercase().replace("0X", "0x"))]?.displayName)
    }

    @Test
    fun anAccountWithNoNameKeepsItsAutoName() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", PairedAccount(1, trading, null, null, null))))

        assertEquals(InternalAccount(displayName = null, autoName = "Vault 1", label = null), accounts[addressKeyOf(trading)])
    }

    @Test
    fun aNameWithNothingVisibleFallsBackToTheAutoNameAndNeverShowsBlank() {
        val accounts = ownAccountsOf(
            listOf(
                wallet(
                    "Vault",
                    PairedAccount(0, savings, "\u200B \u200D", null, null),
                    PairedAccount(1, trading, "Trading", null, null),
                ),
            ),
        )

        assertEquals(mapOf(addressKeyOf(savings) to "Vault 0", addressKeyOf(trading) to "Trading"), accounts.plainNames())
    }

    @Test
    fun anAccountWithNoVisibleNameIsLeftOutSoTheScreenFallsBackToTheAddress() {
        val blank = mapOf("a" to InternalAccount(displayName = "\u200B", autoName = "\u200B  ", label = null))

        assertEquals(emptyMap<String, String>(), blank.plainNames())
    }

    @Test
    fun anAddressOfNoPairedAccountIsNotNamed() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", PairedAccount(0, savings, "Savings", null, null))))

        assertNull(accounts[addressKeyOf(trading)])
    }

    private val seed = PairFixtures.seed
    private val solanaKey = SolanaAccount.publicKey(seed, 0)
    private val solanaOtherKey = SolanaAccount.publicKey(seed, 1)
    private val algorandKey = AlgorandAccount.publicKey(seed, 0)
    private val algorandOtherKey = AlgorandAccount.publicKey(seed, 1)
    private val bitcoin = PairFixtures.account(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
    private val bitcoinTestnet = PairFixtures.account(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)

    private fun wallet(
        name: String,
        bitcoinAccounts: List<BtcWatchOnlyAccount> = emptyList(),
        bitcoinNames: List<String?> = emptyList(),
        solana: List<ByteArray> = emptyList(),
        solanaNames: List<String?> = emptyList(),
        algorand: List<ByteArray> = emptyList(),
        algorandNames: List<String?> = emptyList(),
    ) = PairedWallet(
        id = name,
        name = name,
        fingerprint = 0x5436d724L,
        createdAtMs = 1L,
        bitcoin = bitcoinAccounts,
        solana = solana,
        algorand = algorand,
        bitcoinNames = bitcoinNames,
        solanaNames = solanaNames,
        algorandNames = algorandNames,
    )

    @Test
    fun aSolanaAccountIsNamedByItsAddress() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", solana = listOf(solanaKey, solanaOtherKey), solanaNames = listOf("Daily", null))))

        assertEquals(InternalAccount(displayName = "Daily", autoName = "Vault 0", label = null), accounts[addressKeyOf(SolanaAccount.addressOf(solanaKey))])
        assertEquals(InternalAccount(displayName = null, autoName = "Vault 1", label = null), accounts[addressKeyOf(SolanaAccount.addressOf(solanaOtherKey))])
    }

    @Test
    fun anAlgorandAccountIsNamedByItsAddress() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", algorand = listOf(algorandKey, algorandOtherKey), algorandNames = listOf("Rewards", null))))

        assertEquals(InternalAccount(displayName = "Rewards", autoName = "Vault 0", label = null), accounts[addressKeyOf(AlgorandAccount.addressOf(algorandKey))])
        assertEquals(InternalAccount(displayName = null, autoName = "Vault 1", label = null), accounts[addressKeyOf(AlgorandAccount.addressOf(algorandOtherKey))])
    }

    @Test
    fun aBitcoinAccountIsNamedByItsFirstReceiveAddressInAnyLetterCase() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", bitcoinAccounts = listOf(bitcoin, bitcoinTestnet), bitcoinNames = listOf("Cold storage", null))))
        val address = bitcoin.receiveAddress(0)

        assertEquals(InternalAccount(displayName = "Cold storage", autoName = "Vault 0", label = null), accounts[addressKeyOf(address)])
        assertEquals("Cold storage", accounts[addressKeyOf(address.uppercase())]?.displayName)
        assertEquals(InternalAccount(displayName = null, autoName = "Vault 0", label = null), accounts[addressKeyOf(bitcoinTestnet.receiveAddress(0))])
    }

    @Test
    fun aWalletBuiltWithoutNamesStillNamesEachAccountByItsAutoName() {
        val accounts = ownAccountsOf(listOf(wallet("Vault", solana = listOf(solanaKey))))

        assertEquals("Vault 0", accounts.plainNames()[addressKeyOf(SolanaAccount.addressOf(solanaKey))])
    }

    @Test
    fun theNamesOfEveryChainReachTheNamesTheScreensShow() {
        val own = ownAccountsOf(
            listOf(
                wallet(
                    "Vault", bitcoinAccounts = listOf(bitcoin), bitcoinNames = listOf("Cold storage"),
                    solana = listOf(solanaKey), solanaNames = listOf("Daily"), algorand = listOf(algorandKey), algorandNames = listOf("Rewards"),
                ),
            ),
        ).plainNames()

        assertEquals(
            mapOf(
                addressKeyOf(bitcoin.receiveAddress(0)) to "Cold storage",
                addressKeyOf(SolanaAccount.addressOf(solanaKey)) to "Daily",
                addressKeyOf(AlgorandAccount.addressOf(algorandKey)) to "Rewards",
            ),
            own,
        )
    }
}
