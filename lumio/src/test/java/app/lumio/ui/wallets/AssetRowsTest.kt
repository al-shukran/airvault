package app.lumio.ui.wallets

import app.lumio.chains.ChainHolding
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.evm.EvmChain
import app.lumio.home.NativeAsset
import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import app.lumio.ui.home.AssetIconSpec
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The asset rows of an account: how its coin, its tokens and its SPL or Algorand holdings are keyed, named and priced. */
class AssetRowsTest {
    private val native = NetworkId(Networks.evm(10143), testnet = true)
    private val monad = EvmChain(10143L, "Monad testnet", "MON", 18, true, "https://testnet.monadexplorer.com")
    private val usdcHolding = TokenHolding(monad, Token(10143L, MIXED_CASE_USDC, "USDC", "USD Coin", 6), BigInteger.valueOf(1_250_500_000L), atMs = 0L)
    private val account = WalletAccount(
        walletId = "wallet-demo",
        chain = AccountChain.ETHEREUM,
        index = 0,
        address = "0x" + "a1".repeat(20),
        networkName = "Monad testnet",
        balance = NativeAsset("EVM-10143", "MON", "Monad testnet", native, BigDecimal("12.5")),
    )
    private val prices = mapOf<AssetId, Price>(
        AssetId.Native(native) to Price(usd = 2.0, change24hPct = null, asOfMs = 0L, stale = false),
        AssetId.Erc20.of(native, MIXED_CASE_USDC) to Price(usd = 1.0, change24hPct = null, asOfMs = 0L, stale = false),
    )

    @Test
    fun theNativeCoinIsKeyedNativeAndPricedByItsNetwork() {
        val row = rowsOf(account).single()

        assertEquals(NATIVE_ASSET_KEY, row.assetKey)
        assertEquals("12.5", row.amountText)
        assertEquals("\$25.00", row.fiatText)
        assertEquals(AssetId.Native(native), row.assetId)
        assertEquals(false, row.zero)
    }

    @Test
    fun aZeroNativeCoinIsAZeroRow() {
        val empty = account.copy(balance = NativeAsset("EVM-10143", "MON", "Monad testnet", native, BigDecimal.ZERO))

        assertEquals(true, rowsOf(empty).single().zero)
    }

    @Test
    fun aTokenIsKeyedByItsLowercaseContractAndPricedByIt() {
        val row = AssetRows.of(account.copy(balance = null), listOf(usdcHolding), emptyList(), prices, network = { null }, token = { _, _ -> null }).single()

        assertEquals(MIXED_CASE_USDC.lowercase(), row.assetKey)
        assertEquals("1250.5", row.amountText)
        assertEquals("\$1,250.50", row.fiatText)
        assertEquals(AssetIconSpec.Letter("U"), row.icon)
    }

    @Test
    fun aTokenWithNoPriceHasNoFiat() {
        val row = AssetRows.of(account.copy(balance = null), listOf(usdcHolding), emptyList(), emptyMap(), network = { null }, token = { _, _ -> null }).single()

        assertNull(row.fiatText)
    }

    @Test
    fun anSplTokenIsKeyedByItsMintAndHasNoPrice() {
        val holding = ChainHolding("USDC", "Solana Devnet", BigInteger("1500000"), 6, SPL_MINT)
        val row = AssetRows.of(account.copy(chain = AccountChain.SOLANA, balance = null), emptyList(), listOf(holding), prices, network = { null }, token = { _, _ -> null }).single()

        assertEquals(SPL_MINT, row.assetKey)
        assertEquals("1.5", row.amountText)
        assertNull(row.assetId)
        assertNull(row.fiatText)
    }

    @Test
    fun thePricesAskedForAreTheNativeCoinThenEachToken() {
        assertEquals(listOf(AssetId.Native(native), AssetId.Erc20.of(native, MIXED_CASE_USDC)), AssetRows.pricedIds(account, listOf(usdcHolding)))
    }

    @Test
    fun aTransferIsKeyedByItsContractOrByTheNativeCoin() {
        assertEquals(NATIVE_ASSET_KEY, AssetRows.assetKeyOf(txMoving(contract = null)))
        assertEquals(MIXED_CASE_USDC.lowercase(), AssetRows.assetKeyOf(txMoving(contract = MIXED_CASE_USDC)))
    }

    private fun rowsOf(account: WalletAccount): List<AssetRowModel> =
        AssetRows.of(account, emptyList(), emptyList(), prices, network = { null }, token = { _, _ -> null })

    private fun txMoving(contract: String?) = LumioTx(
        id = "EVM:10143:tx1",
        network = native,
        account = "0x" + "a1".repeat(20),
        hash = "0x01",
        timestampMs = 0L,
        asset = TxAsset(contract = contract, symbol = "X", name = "X", decimals = 18),
        amount = BigInteger.ONE,
        direction = TxDirection.IN,
        fee = null,
        counterparty = null,
        status = TxStatus.CONFIRMED,
        confirmations = 1L,
        inputData = null,
        memo = null,
    )

    private companion object {
        const val MIXED_CASE_USDC = "0xA0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        const val SPL_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    }
}
