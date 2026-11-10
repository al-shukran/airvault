package app.lensly.core.wallet

import kotlin.test.assertEquals
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

class BulkRunTest {

    // The standard test mnemonic. It has 12 words, so it is not a valid Lensly wallet secret, and its seed is derived
    // directly, as HdWalletTest does for the same vectors.
    private val seed = MnemonicUtils.generateSeed(List(11) { "abandon" }.plus("about").joinToString(" "), "")

    @Test
    fun indexZeroIsTheStandardAddress() {
        assertEquals(
            "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            BulkRun.addresses(HdWallet(seed), 0..0).single(),
        )
    }

    @Test
    fun theFirstAccountsMatchTheStandardVectors() {
        assertEquals(
            listOf(
                "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
                "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0",
                "0xb6716976A3ebe8D39aCEB04372f22Ff8e6802D7A",
            ),
            BulkRun.addresses(HdWallet(seed), 0..2),
        )
    }

    @Test
    fun eachBulkAccountEqualsTheSingleAccountDerivationOfItsIndex() {
        val bulk = BulkRun.addresses(HdWallet(seed), 0 until 25)
        val single = HdWallet(seed)
        for (index in 0 until 25) {
            assertEquals(single.address(index), bulk[index], "index $index")
        }
    }

    @Test
    fun accountsAfterExistingOnesEqualTheirSingleDerivation() {
        val single = HdWallet(seed)
        assertEquals(
            (40 until 45).map { single.address(it) },
            BulkRun.addresses(HdWallet(seed), 40 until 45),
        )
    }

    @Test
    fun bulkAccountsMatchTheAddressesOfThePublicXpub() {
        val wallet = HdWallet(seed)
        val xpub = wallet.accountXpub()
        val bulk = BulkRun.addresses(wallet, 0 until 10)
        bulk.forEachIndexed { index, address ->
            assertEquals(AccountAddresses.addressAt(xpub, index), address, "index $index")
        }
    }

    @Test
    fun aRunReportsEachDerivedAccountInOrder() {
        val reported = mutableListOf<Int>()
        BulkRun.addresses(HdWallet(seed), 5 until 10) { reported += it }
        assertEquals(listOf(1, 2, 3, 4, 5), reported)
    }

    @Test
    fun theAccountsOfARunAreDistinct() {
        val bulk = BulkRun.addresses(HdWallet(seed), 0 until 50)
        assertEquals(bulk.size, bulk.toSet().size)
    }
}
