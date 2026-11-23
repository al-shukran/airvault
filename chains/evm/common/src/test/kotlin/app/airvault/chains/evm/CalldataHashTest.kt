package app.airvault.chains.evm

import kotlin.test.Test
import kotlin.test.assertEquals

class CalldataHashTest {
    /** The Keccak-256 of the empty input. It is not the SHA3-256 of the empty input, which starts a7ffc6f8. */
    @Test
    fun theHashOfNoCalldataIsTheKeccakOfTheEmptyInput() {
        assertEquals("0x" + "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", calldataHash(ByteArray(0)))
    }

    @Test
    fun theHashIsLowercaseHexOfTheWholeCalldataWithNoGrouping() {
        val hash = calldataHash(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))

        assertEquals(66, hash.length)
        assertEquals(hash.lowercase(), hash)
    }
}
