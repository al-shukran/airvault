package app.airvault.chains.evm.signer

import kotlin.test.Test
import kotlin.test.assertEquals

class PrintableOrHexTest {
    @Test
    fun aLineBreakOrTabInASignedStringIsShownEscapedOnOneLine() {
        assertEquals("first\\nsecond\\r\\tthird", printableOrHex("first\nsecond\r\tthird"))
    }

    @Test
    fun aPrintableStringIsShownAsItIs() {
        assertEquals("plain text", printableOrHex("plain text"))
    }
}
