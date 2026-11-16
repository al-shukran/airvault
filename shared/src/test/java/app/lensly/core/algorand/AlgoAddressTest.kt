package app.lensly.core.algorand

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.web3j.utils.Numeric

/**
 * Address vectors: the Ledger app's account 123 (app-algorand, develop 62adedc7) and the Mnemonic A accounts 0 to 2
 * from plan section 4.4. The account 123 address is the app-algorand test's; the Mnemonic A addresses came from
 * algosdk 3.9.0 `encodeAddress` when they were recorded.
 */
class AlgoAddressTest {

    @Test
    fun encodesThePublishedAddresses() {
        for ((publicKey, address) in PUBLISHED) {
            assertEquals(address, AlgoAddress.encode(Numeric.hexStringToByteArray(publicKey)))
        }
    }

    @Test
    fun decodeReturnsThePublicKeyOfEachAddress() {
        for ((publicKey, address) in PUBLISHED) {
            assertContentEquals(Numeric.hexStringToByteArray(publicKey), AlgoAddress.decode(address))
        }
    }

    @Test
    fun roundTripsEdgeKeys() {
        val keys = listOf(ByteArray(32), ByteArray(32) { 0xFF.toByte() }, ByteArray(32) { it.toByte() })
        for (key in keys) {
            assertContentEquals(key, AlgoAddress.decode(AlgoAddress.encode(key)))
        }
    }

    @Test
    fun refusesAChangedCharacterThroughTheChecksum() {
        // The padding bits are not touched, so only the checksum can refuse this change.
        val address = PUBLISHED.first().second
        val at = 10
        val changed = address.replaceRange(at, at + 1, if (address[at] == 'A') "B" else "A")
        assertFailsWith<IllegalArgumentException> { AlgoAddress.decode(changed) }
    }

    @Test
    fun refusesNonZeroPaddingBits() {
        // The last character holds 3 address bits and 2 padding bits, which must be zero. This address ends in 'A'.
        val address = PUBLISHED.first().second
        assertEquals('A', address.last())
        assertFailsWith<IllegalArgumentException> { AlgoAddress.decode(address.dropLast(1) + "B") }
    }

    @Test
    fun refusesWrongLengthLowercaseAndCharactersOutsideTheAlphabet() {
        val address = PUBLISHED.first().second
        assertFailsWith<IllegalArgumentException> { AlgoAddress.decode(address.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { AlgoAddress.decode(address + "A") }
        assertFailsWith<IllegalArgumentException> { AlgoAddress.decode(address.lowercase()) }
        assertFailsWith<IllegalArgumentException> { AlgoAddress.decode("1" + address.drop(1)) }
    }

    @Test
    fun encodeRefusesKeysOfAnotherLength() {
        assertFailsWith<IllegalArgumentException> { AlgoAddress.encode(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { AlgoAddress.encode(ByteArray(33)) }
    }

    private companion object {
        /** Public key (hex) and its address. The first row is the Ledger app's account 123. */
        val PUBLISHED = listOf(
            "0dfdbcdb8eebed628cfb4ef70207b86fd0deddca78e90e8c59d6f441e383b377" to
                "BX63ZW4O5PWWFDH3J33QEB5YN7IN5XOKPDUQ5DCZ232EDY4DWN3XKUQRCA",
            "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538" to
                "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ",
            "c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615d" to
                "YEO767URJECB5BKVVIH4ZOBCN5EHGHLU5RCQPRBSCPQ3OBPVMFOYGSM47U",
            "8d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006d" to
                "RU7NDDFYFPLS5K42DPBBU57OWCL3P7LI7FZ7OBQDNUQZUFS5ABWULNOFFM",
        )
    }
}
