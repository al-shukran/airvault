package app.lumio.demo


import app.lumio.btc.EsploraAddressStats
import app.lumio.btc.EsploraInput
import app.lumio.btc.EsploraOutput
import app.lumio.btc.EsploraPrevout
import app.lumio.btc.EsploraSide
import app.lumio.btc.EsploraTx
import app.lumio.data.asset.AssetId
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.*
import app.lumio.data.provider.explorer.ExplorerTx
import java.io.IOException
import java.math.BigInteger
import kotlin.math.sin
import kotlinx.coroutines.awaitCancellation

/** How the demo sources answer: with the mockups' values, with none on mainnet, or with a fault. */
internal enum class DemoProfile {
    /** Every read answers with the demo values. */
    FULL,

    /** Mainnet holds nothing and the test networks hold the demo funds: the empty Portfolio frame. */
    EMPTY,

    /** Every read fails as a dropped connection does. */
    OFFLINE,

    /** Every read fails as a provider error does. */
    ERROR,

    /** No read ever answers, so a screen that has never loaded stays on its loading state. */
    SLOW,
}

/**
 * The one provider of demo mode. It stands in for every network source (prices, market data, EVM, Bitcoin, Solana and Algorand reads, history)
 * and answers each typed request with the fixtures of [DemoFixtures], or with the fault that [profile] asks for. Debug builds only.
 */
internal class DemoProvider(private val profile: () -> DemoProfile) : Provider {
    override val id = "demo"
    override val kind = Kind.DATA
    override val traits = setOf(Trait.KEYLESS)

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig) = true

    override fun defaultLimits(cfg: ProviderConfig) = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 64)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        when (profile()) {
            DemoProfile.OFFLINE -> throw IOException("The demo network is offline.")
            DemoProfile.ERROR -> throw ProviderError.Fatal("The demo provider refuses every read.")
            DemoProfile.SLOW -> awaitCancellation()
            else -> Unit
        }
        val now = System.currentTimeMillis()
        val net = req.net
        val emptyMainnet = profile() == DemoProfile.EMPTY && !net.testnet
        return when (req) {
            is NativeBalance -> when {
                emptyMainnet -> BigInteger.ZERO
                net.isEvm -> DemoFixtures.nativeWei(chainOf(net), req.address)
                net.isAlgo -> DemoFixtures.microAlgos(req.address, mainnet = !net.testnet)
                else -> BigInteger.ZERO
            }
            is SolanaBalances -> req.addresses.mapIndexed { index, _ -> if (emptyMainnet) BigInteger.ZERO else DemoFixtures.lamports(index, !net.testnet) }
            is SplTokenBalances, is AsaHoldings, is SolanaAddressHistory -> emptyList<Any>()
            is ContractCall -> tokenBalance(req, emptyMainnet)
            is Nonce -> 7L
            is TxReceipt -> EvmReceipt(success = true)
            is EstimateGas -> if (req.data == "0x") BigInteger.valueOf(21_000) else BigInteger.valueOf(65_000)
            is GasFees -> GasQuote(baseFee = gwei(80), priorityFee = gwei(1.9))
            is Broadcast -> "0x" + "ab".repeat(32)
            is NativePrice -> quoteOf(net, null, now)
            is TokenPrice -> quoteOf(net, req.token, now)
            is PriceChart -> chartOf(req, now)
            is BitcoinMarket -> MarketQuote(3.41e12, 2.14, now)
            is TrendingCoins -> TrendingList(TRENDING, now)
            is FiatRates -> FiatQuote(mapOf("USD" to 1.0, "EUR" to 0.92, "GBP" to 0.79, "JPY" to 151.2, "INR" to 83.1), now)
            is TokenLogo -> throw ProviderError.NotFound()
            is AddressTxHistory -> if (req.page > 1 || emptyMainnet) emptyList() else history(net, req.address, now)
            is TipHeight -> 900_000L
            is FeeEstimates -> mapOf(1 to 12.0, 3 to 8.0, 6 to 5.0, 144 to 2.0)
            is AddressStats -> stats(req.address, emptyMainnet)
            is AddressTxs -> bitcoinTxs(req.address, now, emptyMainnet)
            is AddressUtxos -> utxos(req.address, emptyMainnet)
            else -> throw ProviderError.Unsupported()
        } as T
    }

    private fun chainOf(net: NetworkId): Long = net.name.toLong()

    private fun gwei(amount: Number): BigInteger = java.math.BigDecimal(amount.toString()).movePointRight(9).toBigInteger()

    private fun tokenBalance(req: ContractCall, emptyMainnet: Boolean): String {
        // Any other call (the batched Multicall3 read of balances) answers "no code here", so the app reads address by address as it does on a chain without Multicall3.
        if (!req.data.startsWith(BALANCE_OF)) return "0x"
        if (emptyMainnet) return "0x" + "0".repeat(64)
        val owner = "0x" + req.data.takeLast(40)
        val units = DemoFixtures.tokenUnits(chainOf(req.net), req.to, owner)
        return "0x" + units.toString(16).padStart(64, '0')
    }

    private fun quoteOf(net: NetworkId, contract: String?, now: Long): PriceQuote {
        if (contract != null) {
            return if (contract.lowercase() in setOf(DemoFixtures.WETH_ARBITRUM)) PriceQuote(DemoFixtures.ETH_USD, -2.41, now) else PriceQuote(1.0, 0.0, now)
        }
        return when {
            net.isBtc -> PriceQuote(DemoFixtures.BTC_USD, 1.82, now)
            net.isSol -> PriceQuote(DemoFixtures.SOL_USD, 4.10, now)
            else -> PriceQuote(DemoFixtures.ETH_USD, -2.41, now)
        }
    }

    /** A smooth, deterministic series that ends at the asset's price, shaped by the sign of its day change. */
    private fun chartOf(req: PriceChart, now: Long): PriceSeries {
        val asset = req.asset
        val quote = quoteOf(asset.net, (asset as? AssetId.Token)?.tokenId, now)
        val count = req.range.points
        val points = (0 until count).map { i ->
            val t = i / (count - 1.0)
            val drift = (quote.change24hPct ?: 0.0) / 100.0 * (t - 1.0)
            val wave = 0.006 * sin(t * 14.0) + 0.003 * sin(t * 37.0)
            PricePoint(now - req.range.windowMs + (req.range.periodMs * i), quote.usd * (1.0 + drift + wave * (1.0 - t)))
        }
        return PriceSeries(points)
    }

    private fun stats(address: String, emptyMainnet: Boolean): EsploraAddressStats {
        val sats = if (emptyMainnet) 0L else DemoFixtures.bitcoinSats[address] ?: 0L
        val side = if (sats > 0) EsploraSide(1, sats, 0, 0, 1) else EsploraSide(0, 0, 0, 0, 0)
        return EsploraAddressStats(chain = side, mempool = EsploraSide(0, 0, 0, 0, 0))
    }

    private fun bitcoinTxs(address: String, now: Long, emptyMainnet: Boolean): List<EsploraTx> {
        val sats = if (emptyMainnet) 0L else DemoFixtures.bitcoinSats[address] ?: return emptyList()
        if (sats == 0L) return emptyList()
        return listOf(
            EsploraTx(
                txid = "c1".repeat(32),
                inputs = listOf(EsploraInput(EsploraPrevout(DemoFixtures.EXTERNAL_BTC, sats + 1_000))),
                outputs = listOf(EsploraOutput(address, sats)),
                blockHeight = 899_990L,
                blockTimeSec = now / 1_000 - 3 * DAY_S,
            ),
        )
    }

    private fun utxos(address: String, emptyMainnet: Boolean): List<Utxo> {
        val sats = if (emptyMainnet) 0L else DemoFixtures.bitcoinSats[address] ?: 0L
        return if (sats > 0) listOf(Utxo("c1".repeat(32), 0, sats, 899_990L)) else emptyList()
    }

    /** The activity of the demo accounts: a deposit from the exchange, a send to a friend, and a token receipt. Other addresses have none. */
    private fun history(net: NetworkId, address: String, now: Long): List<ExplorerTx> {
        val chain = chainOf(net)
        val index = DemoFixtures.evmAddresses.indexOf(address.lowercase())
        if (index < 0) return emptyList()
        val me = DemoFixtures.evmAddresses[index]
        val nowS = now / 1_000
        val gasPrice = gwei(81.9)
        fun normal(n: Int, ageS: Long, from: String, to: String, wei: BigInteger) = ExplorerTx.Normal(
            hash = "0x" + n.toString(16).padStart(2, '0').repeat(32),
            timestampSec = nowS - ageS,
            from = from,
            to = to,
            confirmations = 120L,
            blockNumber = 20_000_000L - ageS / 12,
            valueWei = wei,
            failed = false,
            gasUsed = BigInteger.valueOf(21_000),
            gasPrice = gasPrice,
            input = "0x",
            createdContract = null,
        )
        return when {
            net.testnet && index == 0 -> listOf(
                normal(0x21, 600, me, DemoFixtures.evmAddresses[1], eth("0.25")),
                normal(0x22, 2 * DAY_S, DemoFixtures.EXCHANGE_DEPOSIT, me, eth("5")),
            )
            net.testnet -> listOf(normal(0x23, 600, DemoFixtures.evmAddresses[0], me, eth("0.25")))
            chain == DemoFixtures.ETHEREUM && index == 0 -> listOf(
                normal(0x31, 2 * HOUR_S, DemoFixtures.EXCHANGE_DEPOSIT, me, eth("1")),
                normal(0x32, DAY_S, me, DemoFixtures.LENA_PARK, eth("0.5")),
                ExplorerTx.Token(
                    hash = "0x" + "33".repeat(32),
                    timestampSec = nowS - 3 * DAY_S,
                    from = DemoFixtures.STRANGER,
                    to = me,
                    confirmations = 500L,
                    blockNumber = 19_990_000L,
                    contract = DemoFixtures.USDC_ETHEREUM,
                    name = "USD Coin",
                    symbol = "USDC",
                    decimals = 6,
                    amount = BigInteger.valueOf(5_000_000_000L),
                ),
            )
            chain == DemoFixtures.ARBITRUM && index == 1 -> listOf(normal(0x34, 5 * HOUR_S, DemoFixtures.EXCHANGE_DEPOSIT, me, eth("0.4")))
            else -> emptyList()
        }
    }

    private fun eth(amount: String): BigInteger = java.math.BigDecimal(amount).movePointRight(18).toBigInteger()

    private companion object {
        /** The selector of `balanceOf(address)`. */
        const val BALANCE_OF = "0x70a08231"
        const val HOUR_S = 3_600L
        const val DAY_S = 86_400L
        val TRENDING = listOf(
            TrendingCoin("Bitcoin", "BTC", 1, DemoFixtures.BTC_USD, 1.82),
            TrendingCoin("Ethereum", "ETH", 2, DemoFixtures.ETH_USD, -2.41),
            TrendingCoin("Solana", "SOL", 6, DemoFixtures.SOL_USD, 4.10),
            TrendingCoin("USD Coin", "USDC", 7, 1.0, 0.0),
        )
    }
}
