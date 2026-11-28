package app.lumio.transactions

import app.lumio.data.ETH
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.portfolio.plainText
import app.lumio.watchlist.WatchedAsset
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityViewTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-21T15:00:00Z").toEpochMilli()
    private val counterparty = "0x1234567890abcdef1234567890abcdef12345678"

    private fun tx(
        direction: TxDirection,
        amountWei: String,
        status: TxStatus = TxStatus.CONFIRMED,
        input: String? = null,
        timestampMs: Long = now,
    ) = LumioTx(
        id = "tx-1",
        network = NetworkId(Networks.evm(1), testnet = false),
        account = "0xme",
        hash = "0xhash",
        timestampMs = timestampMs,
        asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
        amount = BigInteger(amountWei),
        direction = direction,
        fee = null,
        counterparty = counterparty,
        status = status,
        confirmations = 1,
        inputData = input,
        memo = null,
    )

    @Test
    fun aNamedPartyKeepsItsNameInTheDetailAndHasNoAddressLine() {
        val line = ActivityText.line(tx(TxDirection.IN, "1000000000000000000"), chain = "Arbitrum", usd = null, party = "Exchange Deposit", zone = zone)

        assertEquals(null, line.address)
    }

    @Test
    fun anUnnamedSenderIsFromItsAddressOnALineOfItsOwn() {
        val line = ActivityText.line(tx(TxDirection.IN, "1000000000000000000"), chain = "Arbitrum", usd = null, party = null, zone = zone)

        assertEquals("Arbitrum · 15:00", line.detail)
        assertEquals("From", line.addressWord)
        assertEquals(counterparty, line.address)
    }

    @Test
    fun rowsAreSectionedByTodayYesterdayOrTheirDay() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals("Today", ActivityText.section(now, now, zone))
        assertEquals("Yesterday", ActivityText.section(now - day, now, zone))
        assertEquals("16 Nov", ActivityText.section(now - 5 * day, now, zone))
    }

    @Test
    fun receivedNamesTheSenderAndShowsItsValue() {
        val line = ActivityText.line(tx(TxDirection.IN, "1000000000000000000"), chain = "Arbitrum", usd = 4285.7, party = "Exchange Deposit", zone = zone)

        assertEquals("Received", line.title)
        assertEquals("From Exchange Deposit · Arbitrum · 15:00", line.detail)
        assertEquals("+1 ETH", line.amount)
        assertEquals(4285.7, line.usd)
        assertTrue(line.incoming)
        assertFalse(line.pending)
    }

    @Test
    fun aPendingCallWithoutAName_isShortAndSaysItIsConfirming() {
        val line = ActivityText.line(
            tx(TxDirection.OUT, "250000000000000000", TxStatus.PENDING, input = "0xa9059cbb0000"),
            chain = "Monad Testnet",
            usd = null,
            party = null,
            zone = zone,
        )

        assertEquals("Contract Call", line.title)
        assertEquals("Confirming · Monad Testnet", line.detail)
        assertEquals("To", line.addressWord)
        assertEquals(counterparty, line.address)
        assertEquals("−0.25 ETH", line.amount)
        assertEquals(null, line.usd)
        assertTrue(line.pending)
    }

    @Test
    fun aZeroAmountIsTheFeeOnly() {
        val line = ActivityText.line(tx(TxDirection.OUT, "0", input = "0x"), chain = "Base", usd = null, party = "FelixStone", zone = zone)

        assertEquals("Sent", line.title)
        assertEquals("Fee only", line.amount)
    }

    @Test
    fun theFilterSentenceNamesItsChoices() {
        assertEquals("Showing all chains, all accounts, newest first.", ActivityFilter().sentence().plainText())
        assertEquals("Showing all chains, all accounts, largest first.", ActivityFilter(sort = ActivitySort.LARGEST).sentence().plainText())
        assertEquals("Showing ETH, all accounts, newest first.", ActivityFilter(coin = WatchedAsset(ETH)).sentence().plainText())
    }
}
