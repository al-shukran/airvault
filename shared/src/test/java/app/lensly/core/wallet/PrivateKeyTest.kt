package app.lensly.core.wallet

import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

class PrivateKeyTest {

    /** A well-known test key. It has never held funds and appears in no fixture other than these tests. */
    private val knownKeyHex = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"
    private val knownKey = Numeric.hexStringToByteArray(knownKeyHex)
    private val knownAddress = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"

    /** The secp256k1 group order n. Keys must be below it. */
    private val orderHex = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"

    private fun parse(text: String): PrivateKey.Parse = PrivateKey.parse(text.toCharArray())

    private fun rejection(text: String): String = assertIs<PrivateKey.Parse.Rejected>(parse(text)).reason

    private fun keyOf(text: String): ByteArray = assertIs<PrivateKey.Parse.Ok>(parse(text)).key

    @Test
    fun sixtyFourHexDigitsParseWithOrWithoutThePrefix() {
        assertContentEquals(knownKey, keyOf(knownKeyHex))
        assertContentEquals(knownKey, keyOf("0x$knownKeyHex"))
        assertContentEquals(knownKey, keyOf("0X$knownKeyHex"))
    }

    @Test
    fun upperCaseDigitsParseToTheSameKey() {
        assertContentEquals(knownKey, keyOf(knownKeyHex.uppercase()))
        assertContentEquals(knownKey, keyOf("0x" + knownKeyHex.uppercase()))
    }

    @Test
    fun theKnownKeyDerivesTheExpectedAddress() {
        assertEquals(knownAddress, AccountAddresses.addressOfPrivateKey(knownKey))
        // web3j derives the same address on its own, so the expectation does not rest on Lensly's code alone.
        val web3jAddress = Keys.getAddress(ECKeyPair.create(knownKey).publicKey)
        assertEquals(knownAddress.lowercase(), "0x" + web3jAddress.lowercase().removePrefix("0x"))
    }

    @Test
    fun aWrongLengthIsRefusedWithTheCountTyped() {
        assertEquals("64 hex digits needed, you typed 63.", rejection(knownKeyHex.dropLast(1)))
        assertEquals("64 hex digits needed, you typed 65.", rejection(knownKeyHex + "0"))
        assertEquals("64 hex digits needed, you typed 62.", rejection("0x" + knownKeyHex.dropLast(2)))
        assertEquals("64 hex digits needed, you typed 1.", rejection("1"))
    }

    @Test
    fun anEmptyEntryAsksForTheDigits() {
        assertEquals("Type 64 hex digits.", rejection(""))
        assertEquals("Type 64 hex digits.", rejection("0x"))
    }

    @Test
    fun aNonHexCharacterIsRefused() {
        val message = "Only 0-9 and a-f are allowed."
        assertEquals(message, rejection("g" + knownKeyHex.drop(1)))
        assertEquals(message, rejection(knownKeyHex.take(10) + " " + knownKeyHex.drop(11)))
        // A second x is not a prefix, so it is refused as a character.
        assertEquals(message, rejection("0xx" + knownKeyHex.drop(1)))
    }

    @Test
    fun aZeroKeyIsRefused() {
        assertEquals("A key cannot be zero.", rejection("0".repeat(64)))
        assertEquals("A key cannot be zero.", rejection("0x" + "0".repeat(64)))
    }

    @Test
    fun aValueAtOrAboveTheCurveOrderIsRefused() {
        val message = "Not valid: at or above the order n."
        assertEquals(message, rejection(orderHex))
        assertEquals(message, rejection(plus(orderHex, 1)))
        assertEquals(message, rejection("f".repeat(64)))
    }

    @Test
    fun theLargestValidKeyIsAccepted() {
        val largest = plus(orderHex, -1)
        assertTrue(PrivateKey.isValid(keyOf(largest)))
    }

    @Test
    fun theSmallestValidKeyIsAccepted() {
        assertContentEquals(ByteArray(31) + 1.toByte(), keyOf("0".repeat(63) + "1"))
    }

    @Test
    fun isValidAgreesWithTheSameRules() {
        assertTrue(PrivateKey.isValid(knownKey))
        assertFalse(PrivateKey.isValid(ByteArray(PrivateKey.BYTES)))
        assertFalse(PrivateKey.isValid(Numeric.hexStringToByteArray(orderHex)))
        assertFalse(PrivateKey.isValid(knownKey.copyOf(31)))
    }

    /** [hex] plus [delta], as 64 lowercase hex digits. */
    private fun plus(hex: String, delta: Int): String =
        BigInteger(hex, 16).add(BigInteger.valueOf(delta.toLong())).toString(16).padStart(64, '0')
}
