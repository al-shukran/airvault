package app.lensly.core.registry

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private const val SPENDER = "0x00112233445566778899aabbccddeeff00112233"
private const val OTHER = "0xffeeddccbbaa99887766554433221100ffeedd00"
private const val SELECTOR_TRANSFER = "a9059cbb"
private const val SELECTOR_APPROVE = "095ea7b3"
private const val SELECTOR_TRANSFER_FROM = "23b872dd"
private const val SELECTOR_INCREASE_ALLOWANCE = "39509351"
private const val SELECTOR_DECREASE_ALLOWANCE = "a457c2d7"
private const val SELECTOR_SET_APPROVAL_FOR_ALL = "a22cb465"
private const val SELECTOR_SAFE_TRANSFER_FROM = "42842e0e"
private val TWO_TO_THE_255: BigInteger = BigInteger.ONE.shiftLeft(255)

private fun hexBytes(hex: String): ByteArray =
    hex.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** One 32-byte word holding [value], as a uint256. */
private fun uintWord(value: BigInteger): ByteArray {
    val out = ByteArray(32)
    val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    raw.copyInto(out, 32 - raw.size)
    return out
}

/** One 32-byte word holding [address] in its low 20 bytes. */
private fun addressWord(address: String): ByteArray {
    val out = ByteArray(32)
    hexBytes(address).copyInto(out, 12)
    return out
}

/** Calldata: a 4-byte selector followed by one 32-byte word per argument. */
private fun calldata(selector: String, vararg words: ByteArray): ByteArray {
    val body = words.fold(ByteArray(0)) { acc, word -> acc + word }
    return hexBytes(selector) + body
}

/** Two-argument calldata of an address and a uint256, such as transfer or approve. */
private fun calldata(selector: String, address: String, amount: BigInteger): ByteArray =
    calldata(selector, addressWord(address), uintWord(amount))

class ContractCallTest {
    @Test
    fun decodesValidTransfer() {
        val data = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.valueOf(1_000_000))

        val call = assertIs<ContractCall.Transfer>(ContractCall.decode(data))
        assertEquals(SPENDER, call.to)
        assertEquals(BigInteger.valueOf(1_000_000), call.value)
    }

    @Test
    fun decodesZeroAmountTransfer() {
        val data = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.ZERO)

        assertEquals(BigInteger.ZERO, (ContractCall.decode(data) as ContractCall.Transfer).value)
    }

    @Test
    fun decodesValidApprove() {
        val data = calldata(SELECTOR_APPROVE, SPENDER, BigInteger.valueOf(500))

        val call = ContractCall.decode(data) as ContractCall.Approve
        assertEquals(SPENDER, call.spender)
        assertEquals(BigInteger.valueOf(500), call.value)
        assertFalse(call.unlimited)
    }

    @Test
    fun maxUint256ApproveIsUnlimited() {
        val call = ContractCall.decode(calldata(SELECTOR_APPROVE, SPENDER, ContractCall.MAX_UINT256))

        assertTrue((call as ContractCall.Approve).unlimited)
    }

    @Test
    fun twoToTheTo255IsUnlimitedAndOneBelowIsNot() {
        val atThreshold = ContractCall.decode(calldata(SELECTOR_APPROVE, SPENDER, TWO_TO_THE_255))
        val belowThreshold = ContractCall.decode(calldata(SELECTOR_APPROVE, SPENDER, TWO_TO_THE_255.subtract(BigInteger.ONE)))

        assertTrue((atThreshold as ContractCall.Approve).unlimited)
        assertFalse((belowThreshold as ContractCall.Approve).unlimited)
    }

    @Test
    fun rejectsEmptyAndSelectorOnlyCalldata() {
        assertNull(ContractCall.decode(ByteArray(0)))
        assertNull(ContractCall.decode(hexBytes(SELECTOR_TRANSFER)))
    }

    @Test
    fun rejectsCalldataOfTheWrongLength() {
        val valid = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.ONE)

        assertNull(ContractCall.decode(valid.copyOf(67)))
        assertNull(ContractCall.decode(valid.copyOf(69)))
        assertNull(ContractCall.decode(calldata(SELECTOR_TRANSFER_FROM, addressWord(SPENDER), uintWord(BigInteger.ONE))))
    }

    @Test
    fun rejectsUnknownSelectorAndNearMiss() {
        assertNull(ContractCall.decode(calldata("70a08231", SPENDER, BigInteger.ONE))) // balanceOf
        assertNull(ContractCall.decode(calldata("a9059cbc", SPENDER, BigInteger.ONE)))
    }

    @Test
    fun rejectsAddressWordWithNonZeroHighBytes() {
        val transfer = calldata(SELECTOR_TRANSFER, SPENDER, BigInteger.ONE)
        transfer[4] = 0x01

        val approve = calldata(SELECTOR_APPROVE, SPENDER, BigInteger.ONE)
        approve[15] = 0x01

        assertNull(ContractCall.decode(transfer))
        assertNull(ContractCall.decode(approve))
    }

    @Test
    fun decodesTransferFromWithItsThreeArguments() {
        val data = calldata(
            SELECTOR_TRANSFER_FROM,
            addressWord(SPENDER),
            addressWord(OTHER),
            uintWord(BigInteger.valueOf(42)),
        )

        val call = assertIs<ContractCall.TransferFrom>(ContractCall.decode(data))
        assertEquals(SPENDER, call.from)
        assertEquals(OTHER, call.to)
        assertEquals(BigInteger.valueOf(42), call.value)
        assertEquals("transferFrom(address,address,uint256)", call.signature)
    }

    @Test
    fun increaseAllowanceIsUnlimitedFromTwoToTheTo255() {
        val unlimited = assertIs<ContractCall.IncreaseAllowance>(
            ContractCall.decode(calldata(SELECTOR_INCREASE_ALLOWANCE, SPENDER, ContractCall.MAX_UINT256)),
        )
        val small = assertIs<ContractCall.IncreaseAllowance>(
            ContractCall.decode(calldata(SELECTOR_INCREASE_ALLOWANCE, SPENDER, BigInteger.TEN)),
        )

        assertTrue(unlimited.unlimited)
        assertFalse(small.unlimited)
        assertEquals("increaseAllowance", small.name)
    }

    @Test
    fun decreaseAllowanceCarriesItsAmountWithoutAnUnlimitedFlag() {
        val call = assertIs<ContractCall.DecreaseAllowance>(
            ContractCall.decode(calldata(SELECTOR_DECREASE_ALLOWANCE, SPENDER, ContractCall.MAX_UINT256)),
        )

        assertEquals(ContractCall.MAX_UINT256, call.subtractedValue)
        assertEquals("decreaseAllowance(address,uint256)", call.signature)
    }

    @Test
    fun setApprovalForAllReadsItsBoolWord() {
        val grant = assertIs<ContractCall.SetApprovalForAll>(
            ContractCall.decode(calldata(SELECTOR_SET_APPROVAL_FOR_ALL, addressWord(SPENDER), uintWord(BigInteger.ONE))),
        )
        val revoke = assertIs<ContractCall.SetApprovalForAll>(
            ContractCall.decode(calldata(SELECTOR_SET_APPROVAL_FOR_ALL, addressWord(SPENDER), uintWord(BigInteger.ZERO))),
        )

        assertEquals(SPENDER, grant.operator)
        assertTrue(grant.approved)
        assertFalse(revoke.approved)
    }

    @Test
    fun setApprovalForAllRefusesABoolWordThatIsNotZeroOrOne() {
        val data = calldata(SELECTOR_SET_APPROVAL_FOR_ALL, addressWord(SPENDER), uintWord(BigInteger.valueOf(2)))

        assertNull(ContractCall.decode(data))
    }

    @Test
    fun decodesSafeTransferFromAsAnNftTransfer() {
        val data = calldata(
            SELECTOR_SAFE_TRANSFER_FROM,
            addressWord(SPENDER),
            addressWord(OTHER),
            uintWord(BigInteger.valueOf(7)),
        )

        val call = assertIs<ContractCall.SafeTransferFrom>(ContractCall.decode(data))
        assertEquals(BigInteger.valueOf(7), call.tokenId)
        assertEquals(OTHER, call.to)
    }
}
