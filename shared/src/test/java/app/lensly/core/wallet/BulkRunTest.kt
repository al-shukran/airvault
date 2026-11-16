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
    fun theFirstAccountsMatchTheLedgerLiveVectors() {
        assertEquals(
            listOf(
                "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
                "0x78839F6054d7ed13918bAe0473BA31b1Ca9D7265",
                "0x07B5FdfEB4E11826D233403Fe8Db0611CCF4c231",
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
    fun bulkAccountKeysAreTheAccountKeysOfTheirIndexes() {
        val single = HdWallet(seed)
        val keys = BulkRun.accountKeys(HdWallet(seed), 3 until 6)
        assertEquals((3 until 6).map { single.accountXpub(it).publicKey.toList() }, keys.map { it.publicKey.toList() })
    }

    @Test
    fun bulkAccountsMatchTheAddressesOfTheirAccountKeys() {
        val wallet = HdWallet(seed)
        val bulk = BulkRun.addresses(wallet, 0 until 10)
        bulk.forEachIndexed { index, address ->
            assertEquals(AccountAddresses.addressAt(wallet.accountXpub(index), 0), address, "index $index")
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
