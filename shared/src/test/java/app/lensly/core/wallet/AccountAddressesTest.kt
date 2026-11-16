package app.lensly.core.wallet

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

private val WALLET by lazy { HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art")) }

class AccountAddressesTest {

    @Test
    fun firstAddressMatchesTheVector() {
        assertEquals(
            "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
            AccountAddresses.addressAt(WALLET.accountXpub(0), 0),
        )
    }

    @Test
    fun publicDerivationFromTheAccountKeyMatchesPrivateDerivation() {
        for (account in 0..3) {
            assertEquals(WALLET.address(account), AccountAddresses.addressAt(WALLET.accountXpub(account), 0), "account $account")
        }
    }

    @Test
    fun theAddressKeyOfAnAccountControlsTheAddressOfThatAccount() {
        for (account in 0..3) {
            val addressKey = AccountAddresses.addressXpub(WALLET.accountXpub(account), 0)
            assertEquals(WALLET.address(account), AccountAddresses.addressOfKey(addressKey), "account $account")
        }
    }

    @Test
    fun theAddressOfAPrivateKeyIsTheAddressOfItsAccount() {
        for (account in 0..2) {
            val key = WALLET.privateKey(account)
            try {
                assertEquals(WALLET.address(account), AccountAddresses.addressOfPrivateKey(key), "account $account")
            } finally {
                key.fill(0)
            }
        }
    }

    @Test
    fun aNegativeIndexIsRefused() {
        assertFailsWith<IllegalArgumentException> { AccountAddresses.addressAt(WALLET.accountXpub(0), -1) }
    }

    @Test
    fun aZeroPrivateKeyIsRefused() {
        assertFailsWith<IllegalArgumentException> { AccountAddresses.addressOfPrivateKey(ByteArray(32)) }
    }
}
