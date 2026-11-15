package app.lumio.data.provider.explorer

import app.lumio.data.provider.ProviderError
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The account-list parsers of the Etherscan family, on answers in the shapes that the docs give. No test reaches a network. */
class ExplorerTxParseTest {
    private val me = "0x" + "ab".repeat(20)
    private val other = "0x" + "cd".repeat(20)
    private val hashA = "0x" + "1a".repeat(32)
    private val hashB = "0x" + "2b".repeat(32)

    @Test
    fun aNormalTransactionKeepsWhatTheMapperNeeds() {
        val row = explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, from = "0x" + "AB".repeat(20), to = other)}]")).single()

        assertEquals(hashA, row.hash)
        assertEquals(1_693_526_411L, row.timestampSec)
        assertEquals(me, row.from)
        assertEquals(other, row.to)
        assertEquals(BigInteger.TEN.pow(18), row.valueWei)
        assertEquals(false, row.failed)
        assertEquals(BigInteger.valueOf(21_000), row.gasUsed)
        assertEquals(BigInteger.valueOf(20_000_000_000L), row.gasPrice)
        assertEquals(1000L, row.confirmations)
        assertNull(row.createdContract)
    }

    @Test
    fun aRevertedTransactionIsFailed() {
        assertTrue(explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, me, other, isError = "1")}]")).single().failed)
    }

    @Test
    fun aContractCreationHasNoRecipientAndNamesTheCreatedContract() {
        val created = "0x" + "ee".repeat(20)
        val row = explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, me, to = "", contractAddress = created)}]")).single()

        assertNull(row.to)
        assertEquals(created, row.createdContract)
    }

    @Test
    fun aPlaceholderInputIsReadAsNoInputAndHexInputIsKept() {
        val placeholder = explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, me, other, input = "deprecated")}]")).single()
        val hex = explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, me, other, input = "0xDEADbeef")}]")).single()

        assertEquals("0x", placeholder.input)
        assertEquals("0xdeadbeef", hex.input)
    }

    @Test
    fun aTokenTransferKeepsItsAssetAndAmount() {
        val row = explorerTokenTxs(explorerAnswer("1", "OK", "[${tokenRow(hashB, me, other, amount = "1500000")}]")).single()

        assertEquals(hashB, row.hash)
        assertEquals("0x" + "ee".repeat(20), row.contract)
        assertEquals("USDC", row.symbol)
        assertEquals("USD Coin", row.name)
        assertEquals(6, row.decimals)
        assertEquals(BigInteger.valueOf(1_500_000), row.amount)
        assertEquals(0, row.ordinal)
    }

    @Test
    fun identicalTokenTransfersOfOneTransactionTakeOrdinalsInTheirOrder() {
        val rows = explorerTokenTxs(
            explorerAnswer("1", "OK", "[${tokenRow(hashA, me, other)},${tokenRow(hashA, me, other)},${tokenRow(hashA, other, me)}]"),
        )

        assertEquals(listOf(0, 1, 0), rows.map { it.ordinal })
    }

    @Test
    fun anEmptyListWithStatusZeroIsAnEmptyAnswerForBothActions() {
        val empty = explorerAnswer("0", "No transactions found", "[]")

        assertEquals(emptyList(), explorerNormalTxs(empty))
        assertEquals(emptyList(), explorerTokenTxs(empty))
    }

    @Test
    fun aRateLimitIsRateLimitedAndAKeyRefusalIsFatal() {
        assertFailsWith<ProviderError.RateLimited> {
            explorerNormalTxs(explorerAnswer("0", "NOTOK", "\"Max rate limit reached, please use API Key for higher rate limit\""))
        }
        assertFailsWith<ProviderError.Fatal> { explorerTokenTxs(explorerAnswer("0", "NOTOK", "\"Invalid API Key\"")) }
    }

    @Test
    fun aStatusZeroThatCarriesRowsIsRefused() {
        assertFailsWith<ProviderError.Fatal> { explorerNormalTxs(explorerAnswer("0", "NOTOK", "[${normalRow(hashA, me, other)}]")) }
    }

    @Test
    fun aRowThatDoesNotMatchTheDocumentedShapeFailsTheWholeAnswer() {
        assertFailsWith<ProviderError.Fatal> { explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow("0x1234", me, other)}]")) }
        assertFailsWith<ProviderError.Fatal> { explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, me, other, value = "1e18")}]")) }
        assertFailsWith<ProviderError.Fatal> { explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, me, other, isError = "2")}]")) }
        assertFailsWith<ProviderError.Fatal> { explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, "0x12", other)}]")) }
        assertFailsWith<ProviderError.Fatal> { explorerNormalTxs(explorerAnswer("2", "OK", "[]")) }
        assertFailsWith<ProviderError.Fatal> { explorerTokenTxs(explorerAnswer("1", "OK", "[${tokenRow(hashA, me, other, decimals = "78")}]")) }
    }

    @Test
    fun aNonJsonAnswerIsTransient() {
        assertFailsWith<ProviderError.Transient> { explorerNormalTxs("<html>portal</html>") }
    }

    @Test
    fun aListThatHoldsARowOfAnotherAccountIsRefused() {
        val withStranger = explorerNormalTxs(
            explorerAnswer("1", "OK", "[${normalRow(hashA, me, other)},${normalRow(hashB, other, "0x" + "ff".repeat(20))}]"),
        )
        val ownOnly = explorerNormalTxs(explorerAnswer("1", "OK", "[${normalRow(hashA, me, other)}]"))

        assertFailsWith<ProviderError.Fatal> { withStranger.involving(me) }
        assertEquals(1, ownOnly.involving(me).size)
    }
}
