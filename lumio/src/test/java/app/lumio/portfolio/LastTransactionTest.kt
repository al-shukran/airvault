package app.lumio.portfolio

import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxDetail
import java.math.BigInteger
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LastTransactionTest {
    private val network = NetworkId(Networks.evm(1), testnet = false)

    private fun page(account: String, id: String): TransactionPage {
        val tx = LumioTx(
            id = id,
            network = network,
            account = account,
            hash = "01".repeat(32),
            timestampMs = 1_700_000_000_000L,
            asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
            amount = BigInteger.ONE,
            direction = TxDirection.OUT,
            fee = null,
            counterparty = null,
            status = TxStatus.CONFIRMED,
            confirmations = 1L,
            inputData = null,
            memo = null,
        )
        val detail = TxDetail(tx = tx, usd = null, comment = null, chain = "Ethereum", feeText = null, explorerUrl = null, flags = emptyList())
        return TransactionPage(detail, accounts = emptyMap(), wallet = null, book = emptyList())
    }

    @Test
    fun aReadIsReturnedOnlyForItsOwnAccountAndTransaction() {
        val cache = LastTransaction()
        val read = page("0xaaaa", "tx-1")

        cache.put(read)

        assertSame(read, cache.get("0xaaaa", "tx-1"))
        assertNull(cache.get("0xaaaa", "tx-2"))
        assertNull(cache.get("0xbbbb", "tx-1"))
    }

    @Test
    fun aNewReadReplacesTheLastOneAndClearForgetsIt() {
        val cache = LastTransaction()
        val first = page("0xaaaa", "tx-1")
        val second = page("0xaaaa", "tx-2")

        cache.put(first)
        cache.put(second)
        assertNull(cache.get("0xaaaa", "tx-1"))
        assertSame(second, cache.get("0xaaaa", "tx-2"))

        cache.clear()
        assertNull(cache.get("0xaaaa", "tx-2"))
    }
}
