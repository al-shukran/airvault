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
            AccountAddresses.addressAt(WALLET.accountXpub(), 0),
        )
    }

    @Test
    fun publicDerivationFromTheXpubMatchesPrivateDerivation() {
        val xpub = WALLET.accountXpub()
        for (index in 0..3) {
            assertEquals(WALLET.address(index), AccountAddresses.addressAt(xpub, index), "index $index")
        }
    }

    @Test
    fun theAddressOfAPrivateKeyIsTheAddressOfItsAccount() {
        for (index in 0..2) {
            val key = WALLET.privateKey(index)
            try {
                assertEquals(WALLET.address(index), AccountAddresses.addressOfPrivateKey(key), "index $index")
            } finally {
                key.fill(0)
            }
        }
    }

    @Test
    fun aNegativeIndexIsRefused() {
        assertFailsWith<IllegalArgumentException> { AccountAddresses.addressAt(WALLET.accountXpub(), -1) }
    }

    @Test
    fun aZeroPrivateKeyIsRefused() {
        assertFailsWith<IllegalArgumentException> { AccountAddresses.addressOfPrivateKey(ByteArray(32)) }
    }
}
