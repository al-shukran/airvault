package app.lensly.core.registry

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private const val SPENDER = "0x00112233445566778899aabbccddeeff00112233"
private const val SELECTOR_TRANSFER = "a9059cbb"
private const val SELECTOR_APPROVE = "095ea7b3"
private const val SELECTOR_TRANSFER_FROM = "23b872dd"

private fun hexBytes(hex: String): ByteArray =
    hex.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Builds 4 + 32 + 32 bytes of calldata: selector, left-padded address word, uint256 word. */
private fun calldata(selector: String, address: String, amount: BigInteger): ByteArray {
    val out = ByteArray(68)
    hexBytes(selector).copyInto(out, 0)
    hexBytes(address).copyInto(out, 4 + 12)
    val raw = amount.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    raw.copyInto(out, 68 - raw.size)
    return out
}

class Erc20CallTest {
    @Test
    fun decodesValidTransfer() {
        val data = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.valueOf(1_000_000))

        assertEquals(
            Erc20Call.Transfer(SPENDER, BigInteger.valueOf(1_000_000)),
            Erc20Call.decode(data),
        )
    }

    @Test
    fun decodesZeroAmountTransfer() {
        val data = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.ZERO)

        assertEquals(Erc20Call.Transfer(SPENDER, BigInteger.ZERO), Erc20Call.decode(data))
    }

    @Test
    fun decodesValidApprove() {
        val data = calldata(SELECTOR_APPROVE, SPENDER, BigInteger.valueOf(500))

        val call = Erc20Call.decode(data) as Erc20Call.Approve
        assertEquals(SPENDER, call.spender)
        assertEquals(BigInteger.valueOf(500), call.amount)
        assertFalse(call.unlimited)
    }

    @Test
    fun flagsUnlimitedApprove() {
        val data = calldata(SELECTOR_APPROVE, SPENDER, Erc20Call.MAX_UINT256)

        val call = Erc20Call.decode(data) as Erc20Call.Approve
        assertTrue(call.unlimited)
    }

    @Test
    fun oneBelowMaxIsNotUnlimited() {
        val data = calldata(SELECTOR_APPROVE, SPENDER, Erc20Call.MAX_UINT256.subtract(BigInteger.ONE))

        val call = Erc20Call.decode(data) as Erc20Call.Approve
        assertFalse(call.unlimited)
    }

    @Test
    fun rejectsEmptyCalldata() {
        assertNull(Erc20Call.decode(ByteArray(0)))
    }

    @Test
    fun rejectsShortAndLongCalldata() {
        val valid = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.ONE)

        assertNull(Erc20Call.decode(valid.copyOf(67)))
        assertNull(Erc20Call.decode(valid.copyOf(69)))
    }

    @Test
    fun rejectsUnknownSelector() {
        val data = calldata(SELECTOR_TRANSFER_FROM, SPENDER, BigInteger.ONE)

        assertNull(Erc20Call.decode(data))
    }

    @Test
    fun rejectsNearMissSelector() {
        val data = calldata("a9059cbc", SPENDER, BigInteger.ONE)

        assertNull(Erc20Call.decode(data))
    }

    @Test
    fun rejectsAddressWordWithNonZeroHighBytes() {
        val transfer = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.ONE)
        transfer[4] = 0x01

        val approve = calldata(SELECTOR_APPROVE, SPENDER, BigInteger.ONE)
        approve[15] = 0x01

        assertNull(Erc20Call.decode(transfer))
        assertNull(Erc20Call.decode(approve))
    }
}
