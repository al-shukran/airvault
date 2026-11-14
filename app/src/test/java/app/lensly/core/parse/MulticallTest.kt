package app.lensly.core.parse

import app.lensly.core.registry.ContractCall
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.web3j.utils.Numeric

private const val TOKEN = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
private const val RECIPIENT = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
private const val MULTICALL3 = "0xca11bde05977b3631167028862be2a173976ca11"

/** One 32-byte word of [value], as hex without 0x. */
private fun word(value: Int): String = word(BigInteger.valueOf(value.toLong()))

private fun word(value: BigInteger): String = value.toString(16).padStart(64, '0')

private fun addressWord(address: String): String = address.removePrefix("0x").lowercase().padStart(64, '0')

/** A dynamic byte string as hex without 0x: its length, then its bytes padded to a whole word. */
private fun bytesHex(hex: String): String {
    val size = hex.length / 2
    return word(size) + hex.padEnd((size + 31) / 32 * 64, '0')
}

/** A dynamic array as hex without 0x: its length, the offset of each element, then the elements, each already hex. */
private fun arrayHex(elements: List<String>): String {
    var offset = 32 * elements.size
    val offsets = elements.joinToString("") { element -> word(offset).also { offset += element.length / 2 } }
    return word(elements.size) + offsets + elements.joinToString("")
}

/** A call of [signature] with its arguments as hex words, without 0x. */
private fun callHex(signature: String, vararg words: String): String =
    WhitelistEntry.selectorOf(signature).removePrefix("0x") + words.joinToString("")

/** The calldata of `multicall(bytes[])` over [calls], each call as hex without 0x. */
private fun multicallHex(calls: List<String>): String =
    WhitelistEntry.selectorOf("multicall(bytes[])").removePrefix("0x") + word(32) + arrayHex(calls.map { bytesHex(it) })

private fun bytesOf(hex: String): ByteArray = Numeric.hexStringToByteArray(hex)

private val TRANSFER = callHex("transfer(address,uint256)", addressWord(RECIPIENT), word(10))
private val APPROVE = callHex("approve(address,uint256)", addressWord(TOKEN), word(1))

class MulticallTest {

    @Test
    fun everyShapeSelectsTheSelectorOfItsSignature() {
        for (shape in MulticallShape.entries) {
            assertEquals("0x" + shape.selector, WhitelistEntry.selectorOf(shape.signature), shape.signature)
        }
    }

    @Test
    fun aMulticallOfTwoCallsReadsEachCallAndItsDecodedForm() {
        val data = bytesOf(multicallHex(listOf(TRANSFER, APPROVE)))

        val batch = assertNotNull(multicallOf(data))

        assertNull(batch.deadline)
        assertEquals(listOf(TRANSFER, APPROVE), batch.calls.map { it.calldata.toHex() })
        assertIs<ContractCall.Transfer>(batch.calls[0].call)
        assertIs<ContractCall.Approve>(batch.calls[1].call)
        assertEquals(MulticallShape.BYTES_ARRAY, encodesAs(data, batch))
    }

    @Test
    fun aDeadlineIsReadAheadOfTheCalls() {
        val selector = WhitelistEntry.selectorOf("multicall(uint256,bytes[])").removePrefix("0x")
        val data = bytesOf(selector + word(1_700_000_000) + word(64) + arrayHex(listOf(bytesHex(APPROVE))))

        val batch = assertNotNull(multicallOf(data))

        assertEquals(BigInteger.valueOf(1_700_000_000L), batch.deadline)
        assertIs<ContractCall.Approve>(batch.calls.single().call)
        assertEquals(MulticallShape.DEADLINE_BYTES_ARRAY, encodesAs(data, batch))
    }

    @Test
    fun aMulticall3CallCarriesItsTargetFlagsAndValue() {
        val tuple = addressWord(TOKEN) + word(1) + word(BigInteger.TEN.pow(15)) + word(128) + bytesHex(TRANSFER)
        val selector = WhitelistEntry.selectorOf("aggregate3Value((address,bool,uint256,bytes)[])").removePrefix("0x")
        val data = bytesOf(selector + word(32) + arrayHex(listOf(tuple)))

        val batch = assertNotNull(multicallOf(data))
        val only = batch.calls.single()

        assertEquals(TOKEN, only.target)
        assertEquals(true, only.allowFailure)
        assertEquals(BigInteger.TEN.pow(15), only.value)
        assertIs<ContractCall.Transfer>(only.call)
        assertEquals(MulticallShape.AGGREGATE3_VALUE, encodesAs(data, batch))
    }

    @Test
    fun aNestedMulticallReadsItsOwnCallsOneLevelDown() {
        val inner = multicallHex(listOf(TRANSFER, APPROVE))
        val data = bytesOf(multicallHex(listOf(inner)))

        val outer = assertNotNull(multicallOf(data))
        val nested = assertNotNull(outer.calls.single().nested)

        assertNull(outer.calls.single().call)
        assertEquals(2, nested.calls.size)
        assertIs<ContractCall.Transfer>(nested.calls[0].call)
        assertEquals(2, outer.deepestLevel())
        assertEquals(3, outer.callCount())
        assertEquals(MulticallShape.BYTES_ARRAY, encodesAs(bytesOf(inner), nested))
    }

    @Test
    fun aBatchWhoseOffsetsOrLengthsLeaveItsBytesIsNotRead() {
        val selector = WhitelistEntry.selectorOf("multicall(bytes[])").removePrefix("0x")

        assertNull(multicallOf(bytesOf(selector + word(4096) + word(0))), "the array starts past the end")
        assertNull(multicallOf(bytesOf(selector + word(32))), "there is no array")
        assertNull(multicallOf(bytesOf(selector + word(32) + word(1) + word(4096))), "a call starts past the end")
        assertNull(multicallOf(bytesOf(selector + word(32) + word(1) + word(32) + word(1 shl 20))), "a call is longer than the data")
    }

    @Test
    fun aBatchOfMoreCallsThanTheSandboxReadsIsNotRead() {
        fun batch(depth: Int): String = if (depth == 0) TRANSFER else multicallHex(List(8) { batch(depth - 1) })

        assertNotNull(multicallOf(bytesOf(batch(2))), "72 calls")
        assertNull(multicallOf(bytesOf(batch(3))), "584 calls")
    }

    @Test
    fun aCallWithAnUnknownSelectorIsReadAsUndecodedAndNotAsAFailure() {
        val unknown = callHex("balanceOf(address)", addressWord(RECIPIENT))
        val batch = assertNotNull(multicallOf(bytesOf(multicallHex(listOf(unknown)))))

        assertNull(batch.calls.single().call)
        assertNull(batch.calls.single().nested)
        assertContentEquals(bytesOf(unknown), batch.calls.single().calldata)
    }

    @Test
    fun theCallsOfAMulticall3BatchEncodeBackToTheirBytes() {
        val calldata = bytesOf(TRANSFER)
        val call = InnerCall(
            target = TOKEN,
            allowFailure = true,
            value = BigInteger.TEN.pow(15),
            calldata = calldata,
            call = ContractCall.decode(calldata),
            nested = null,
        )
        val tuple = addressWord(TOKEN) + word(1) + word(BigInteger.TEN.pow(15)) + word(128) + bytesHex(TRANSFER)
        val selector = WhitelistEntry.selectorOf("aggregate3Value((address,bool,uint256,bytes)[])").removePrefix("0x")

        val encoded = assertNotNull(encodeMulticall(MulticallShape.AGGREGATE3_VALUE, null, listOf(call)))

        assertContentEquals(bytesOf(selector + word(32) + arrayHex(listOf(tuple))), encoded)
    }

    @Test
    fun aTreeThatDiffersFromTheSignedBytesDoesNotEncodeToThem() {
        val data = bytesOf(multicallHex(listOf(TRANSFER, APPROVE)))
        val honest = assertNotNull(multicallOf(data))
        val first = honest.calls.first()
        val flipped = first.calldata.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        assertEquals(MulticallShape.BYTES_ARRAY, encodesAs(data, honest))
        assertNull(encodesAs(data, honest.copy(calls = listOf(first.copy(calldata = flipped), honest.calls[1]))), "a changed call")
        assertNull(encodesAs(data, honest.copy(deadline = BigInteger.ONE)), "a deadline the shape lacks")
        assertNull(encodesAs(data, honest.copy(calls = honest.calls.map { it.copy(target = TOKEN) })), "a target the shape lacks")
        assertNull(encodesAs(data, honest.copy(calls = honest.calls.take(1))), "a call the signed bytes do not have")
    }

    @Test
    fun aCallThatDoesNotFitItsShapeDoesNotEncode() {
        val calldata = bytesOf(TRANSFER)
        val untargeted = InnerCall(target = null, allowFailure = null, value = null, calldata = calldata, call = null, nested = null)
        val badAddress = untargeted.copy(target = "0x1234")
        val missingFlag = untargeted.copy(target = MULTICALL3)

        assertNull(encodeMulticall(MulticallShape.AGGREGATE3, null, listOf(missingFlag)), "aggregate3 needs allowFailure")
        assertNull(encodeMulticall(MulticallShape.AGGREGATE, null, listOf(badAddress)), "a target must be 20 bytes")
        assertNull(encodeMulticall(MulticallShape.BYTES_ARRAY, BigInteger.ONE, listOf(untargeted)), "no deadline in this shape")
        assertNull(encodeMulticall(MulticallShape.DEADLINE_BYTES_ARRAY, null, listOf(untargeted)), "the deadline is required")
    }
}
