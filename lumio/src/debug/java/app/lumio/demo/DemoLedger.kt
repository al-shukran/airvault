package app.lumio.demo

import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.tx.BTC_ASSET
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxQuery
import app.lumio.data.tx.TxSort
import app.lumio.data.tx.TxStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Debug-only: the fixed transaction history of the demo portfolio: 16 transactions, 18 rows, sent, received, self transfers and swaps, from five minutes
 * to fourteen days old. A swap is two rows with one hash, the token it sold and the coin it bought, as a swap is in an account's history. Every address
 * is a public example (EIP-55 and BIP-173 test vectors), and every hash is a digest of a label, so no explorer knows any of them.
 *
 * [matching] applies a [TxQuery] the way the store does, with two demo rules. The side of the rows follows the query's networks, mainnet or testnet, and
 * not the owner's enabled chains, since the demo shows all of its coins. And the rows are never spam.
 */
internal object DemoLedger {
    private const val HOUR_MS = 3_600_000L
    private val now = System.currentTimeMillis()

    private val ACCOUNT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val BTC_ACCOUNT = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    private val BTC_TESTNET_ACCOUNT = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"
    private const val PEER_A = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private const val PEER_B = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private const val PEER_C = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
    private const val PEER_D = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
    private const val BTC_PEER = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
    private const val BTC_OTHER = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    private const val TESTNET_PEER = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"

    private val ETHEREUM = NetworkId(Family.EVM, "1", false)
    private val BASE = NetworkId(Family.EVM, "8453", false)
    private val MONAD_TESTNET = NetworkId(Family.EVM, "10143", true)
    private val SEPOLIA = NetworkId(Family.EVM, "11155111", true)
    private val BITCOIN = NetworkId(Family.BTC, "mainnet", false)
    private val BITCOIN_TESTNET4 = NetworkId(Family.BTC, "testnet4", true)

    private val ETH = TxAsset(null, "ETH", "Ethereum", 18)
    private val BASE_ETH = TxAsset(null, "ETH", "Base", 18)
    private val USDC = TxAsset(DemoAssets.USDC_ETHEREUM, "USDC", "USD Coin", 6)
    private val BASE_USDC = TxAsset(DemoAssets.USDC_BASE, "USDC", "USD Coin", 6)
    private val USDT = TxAsset(DemoAssets.USDT_ETHEREUM, "USDT", "Tether USD", 6)
    private val MON = TxAsset(null, "MON", "Monad Testnet", 18)
    private val SEPOLIA_ETH = TxAsset(null, "ETH", "Ethereum Sepolia", 18)

    /** The fixed history, in the order it was written. [matching] puts it in the order a query asks for. */
    internal val rows: List<LumioTx> by lazy {
        listOf(
            // Mainnet: Ethereum, Base and Bitcoin.
            row(ETHEREUM, ACCOUNT, ETH, TxDirection.OUT, eth("0.05"), eth("0.00042"), PEER_A, TxStatus.CONFIRMED, 2.0, "eth-out"),
            row(ETHEREUM, ACCOUNT, USDC, TxDirection.OUT, units("250", 6), eth("0.00118"), PEER_C, TxStatus.CONFIRMED, 24.0, "swap-usdc-eth"),
            row(ETHEREUM, ACCOUNT, ETH, TxDirection.IN, eth("0.0731"), null, PEER_C, TxStatus.CONFIRMED, 24.0, "swap-usdc-eth"),
            row(BITCOIN, BTC_ACCOUNT, BTC_ASSET, TxDirection.IN, btc("0.05"), null, BTC_PEER, TxStatus.CONFIRMED, 36.0, "btc-in"),
            row(BASE, ACCOUNT, BASE_ETH, TxDirection.OUT, eth("0.01"), eth("0.00001"), PEER_D, TxStatus.PENDING, 0.08, "base-out-pending"),
            row(BASE, ACCOUNT, BASE_ETH, TxDirection.SELF, eth("0.1"), eth("0.00002"), null, TxStatus.CONFIRMED, 72.0, "base-self"),
            row(BASE, ACCOUNT, BASE_USDC, TxDirection.IN, units("642.18", 6), null, PEER_C, TxStatus.CONFIRMED, 144.0, "base-usdc-in"),
            row(BITCOIN, BTC_ACCOUNT, BTC_ASSET, TxDirection.OUT, btc("0.012"), btc("0.0000184"), BTC_OTHER, TxStatus.CONFIRMED, 96.0, "btc-out"),
            row(ETHEREUM, ACCOUNT, USDT, TxDirection.OUT, units("120", 6), eth("0.00031"), PEER_A, TxStatus.CONFIRMED, 9.0, "usdt-out"),
            row(ETHEREUM, ACCOUNT, USDC, TxDirection.IN, units("1000", 6), null, PEER_B, TxStatus.CONFIRMED, 288.0, "usdc-in"),
            row(ETHEREUM, ACCOUNT, ETH, TxDirection.OUT, eth("0.3"), eth("0.00096"), PEER_D, TxStatus.FAILED, 192.0, "eth-failed"),
            row(BASE, ACCOUNT, BASE_USDC, TxDirection.OUT, units("300", 6), eth("0.00004"), PEER_C, TxStatus.CONFIRMED, 240.0, "swap-base-usdc"),
            row(BASE, ACCOUNT, BASE_ETH, TxDirection.IN, eth("0.0879"), null, PEER_C, TxStatus.CONFIRMED, 240.0, "swap-base-usdc"),
            // Testnet: Monad, Bitcoin testnet4 and Sepolia.
            row(MONAD_TESTNET, ACCOUNT, MON, TxDirection.IN, eth("25"), null, PEER_B, TxStatus.CONFIRMED, 24.0, "mon-in"),
            row(MONAD_TESTNET, ACCOUNT, MON, TxDirection.OUT, eth("2.5"), eth("0.0009"), PEER_A, TxStatus.CONFIRMED, 48.0, "mon-out"),
            row(BITCOIN_TESTNET4, BTC_TESTNET_ACCOUNT, BTC_ASSET, TxDirection.OUT, btc("0.0008"), btc("0.00001"), TESTNET_PEER, TxStatus.CONFIRMED, 144.0, "tbtc-out"),
            row(BITCOIN_TESTNET4, BTC_TESTNET_ACCOUNT, BTC_ASSET, TxDirection.IN, btc("0.005"), null, TESTNET_PEER, TxStatus.CONFIRMED, 336.0, "tbtc-in"),
            row(SEPOLIA, ACCOUNT, SEPOLIA_ETH, TxDirection.IN, eth("0.5"), null, PEER_B, TxStatus.CONFIRMED, 240.0, "sepolia-in"),
        )
    }

    /** The rows that [query] matches, in its order. Each filter of the query applies, and a row that ties keeps the order of its id. */
    fun matching(txs: List<LumioTx>, query: TxQuery): List<LumioTx> {
        val side = query.networks.firstOrNull()?.testnet
        val words = query.text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val primary = comparatorOf(query.sort)
        val ordered = if (query.descending) primary.reversed() else primary
        return txs
            .filter { tx -> side == null || tx.network.testnet == side }
            .filter { tx -> query.account == null || tx.account.equals(query.account, ignoreCase = true) }
            .filter { tx -> query.assets.isEmpty() || query.assets.any { it.network == tx.network && it.contract == tx.asset.contract } }
            .filter { tx -> query.fromMs == null || tx.timestampMs >= query.fromMs }
            .filter { tx -> query.untilMs == null || tx.timestampMs < query.untilMs }
            .filter { tx -> query.directions.isEmpty() || tx.direction in query.directions }
            .filter { tx -> query.statuses.isEmpty() || tx.status in query.statuses }
            .filter { tx -> query.amountAbove == null || whole(tx) > query.amountAbove }
            .filter { tx -> query.amountBelow == null || whole(tx) < query.amountBelow }
            .filter { tx -> words.all { word -> textOf(tx).any { it.contains(word, ignoreCase = true) } } }
            .sortedWith(ordered.thenBy { it.id })
    }

    private fun comparatorOf(sort: TxSort): Comparator<LumioTx> = when (sort) {
        TxSort.TIMESTAMP -> compareBy { it.timestampMs }
        TxSort.AMOUNT -> compareBy { whole(it) }
        TxSort.FEE -> compareBy { fee(it) }
        TxSort.ASSET -> compareBy { it.asset.symbol.lowercase() }
        TxSort.DIRECTION -> compareBy { it.direction.ordinal }
        TxSort.STATUS -> compareBy { it.status.ordinal }
        TxSort.NETWORK -> compareBy { it.network.chain }
    }

    /** The amount in whole coins, which is how an amount filter and an amount sort compare every coin. */
    private fun whole(tx: LumioTx): BigDecimal = BigDecimal(tx.amount).movePointLeft(tx.asset.decimals)

    /** The fee in whole coins of the network's native coin, or zero when the account paid none. */
    private fun fee(tx: LumioTx): BigDecimal = tx.fee?.let { BigDecimal(it).movePointLeft(if (tx.network.family == Family.BTC) 8 else 18) } ?: BigDecimal.ZERO

    /** The words a search reads: the hash, the counterparty, the asset and its name, and the memo. */
    private fun textOf(tx: LumioTx): List<String> = listOf(tx.hash, tx.counterparty.orEmpty(), tx.asset.symbol, tx.asset.name, tx.memo.orEmpty())

    private fun row(
        net: NetworkId,
        account: String,
        asset: TxAsset,
        direction: TxDirection,
        amount: BigInteger,
        fee: BigInteger?,
        counterparty: String?,
        status: TxStatus,
        hoursAgo: Double,
        label: String,
    ): LumioTx {
        val hash = hashOf(label, net)
        // A token row adds its contract to the id, so a swap's two rows, which share a hash, keep distinct ids as the store keeps them.
        val suffix = asset.contract?.let { ":$it" }.orEmpty()
        return LumioTx(
            id = "${net.family.name}:${net.chain}:$hash$suffix",
            network = net,
            account = account,
            hash = hash,
            timestampMs = now - (hoursAgo * HOUR_MS).toLong(),
            asset = asset,
            amount = amount,
            direction = direction,
            fee = fee,
            counterparty = counterparty,
            status = status,
            confirmations = when (status) {
                TxStatus.PENDING -> 0L
                else -> if (net.family == Family.BTC) 6L else 24L
            },
            inputData = null,
            memo = null,
        )
    }

    /** A 32-byte digest of [label] as hex: 0x and 64 hex digits on EVM, 64 hex digits on Bitcoin. */
    private fun hashOf(label: String, net: NetworkId): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("airvault-demo:${net.chain}:$label".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return if (net.family == Family.EVM) "0x$hex" else hex
    }

    private fun eth(amount: String): BigInteger = units(amount, 18)

    private fun btc(amount: String): BigInteger = units(amount, 8)

    private fun units(amount: String, decimals: Int): BigInteger = BigDecimal(amount).movePointRight(decimals).toBigIntegerExact()

    private val WHITESPACE = Regex("\\s+")
}
