package app.lumio.pair

import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The Ethereum accounts of the paired wallets, each named as the pairing named it. A pairing names only its Ethereum accounts. */
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
}
