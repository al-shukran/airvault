package app.lumio.wallets

import androidx.compose.ui.graphics.Color
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.Price
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import app.lumio.ui.kit.AllocationMath
import app.lumio.ui.kit.AllocationPalette
import app.lumio.ui.kit.AllocationSlice
import app.lumio.ui.kit.AllocationState
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The segments the Wallets home's ring draws for all wallets: the hidden and the empty states, one asset, an asset that two wallets hold, tokens, and the
 * small assets that merge into Other.
 */
class WalletsAllocationTest {

    @Test
    fun hiddenTotalsDrawNoSegment() {
        val view = viewOf(accounts = listOf(account("w1", "4")), prices = mapOf(MON to price(2.5)))
        assertEquals(AllocationState.Hidden, WalletsAllocation.state(view, shown = false, background = DARK))
    }

    @Test
    fun withoutAPriceTheRingIsEmpty() {
        val view = viewOf(accounts = listOf(account("w1", "4")))
        assertEquals(AllocationState.Empty, WalletsAllocation.state(view, shown = true, background = DARK))
    }

    @Test
    fun zeroBalancesAreEmpty() {
        val view = viewOf(accounts = listOf(account("w1", "0")), prices = mapOf(MON to price(2.5)))
        assertEquals(AllocationState.Empty, WalletsAllocation.state(view, shown = true, background = DARK))
    }

    @Test
    fun aZeroAssetTakesNoSegment() {
        val view = viewOf(
            accounts = listOf(account("w1", "0")),
            tokens = mapOf("w1" to listOf(usdc("10"))),
            prices = mapOf(MON to price(2.5), USDC to price(1.0)),
        )
        assertEquals(listOf(USDC_ID), ready(view).slices.map { it.assetId })
    }

    @Test
    fun aTokenWithoutAPriceTakesNoSegment() {
        val view = viewOf(
            accounts = listOf(account("w1", "2")),
            tokens = mapOf("w1" to listOf(usdc("10"))),
            prices = mapOf(MON to price(2.5)),
        )
        assertEquals(listOf(MON_ID), ready(view).slices.map { it.assetId })
    }

    @Test
    fun aSingleAssetIsOneWholeSegment() {
        val view = viewOf(accounts = listOf(account("w1", "4")), prices = mapOf(MON to price(2.5)))
        val state = ready(view)
        assertEquals(listOf(AllocationSlice(MON_ID, 10.0, AllocationPalette.colorOf(MON_ID, DARK))), state.slices)
        val shares = AllocationMath.shares(state.slices, AllocationPalette.otherOf(DARK))
        assertEquals(listOf(MON_ID), shares.map { it.assetId })
        assertEquals(1f, shares.single().fraction, 0f)
    }

    @Test
    fun anAssetHeldByTwoWalletsIsOneSegment() {
        val view = viewOf(accounts = listOf(account("w1", "1"), account("w2", "3")), prices = mapOf(MON to price(2.0)))
        val slices = ready(view).slices
        assertEquals(listOf(MON_ID), slices.map { it.assetId })
        assertEquals(8.0, slices.single().usd, 0.0)
    }

    @Test
    fun tokensJoinTheSegmentsAtTheirOwnPrice() {
        val view = viewOf(
            accounts = listOf(account("w1", "1")),
            tokens = mapOf("w1" to listOf(usdc("10"))),
            prices = mapOf(MON to price(2.5), USDC to price(1.0)),
        )
        val state = ready(view)
        assertEquals(setOf(MON_ID, USDC_ID), state.slices.map { it.assetId }.toSet())
        assertEquals(12.5, AllocationMath.total(state.slices), 1e-9)
    }

    @Test
    fun tinyAssetsMergeIntoOther() {
        val tokens = (1..10).map { index -> tokenOf("0x%040x".format(index), "1") }
        val view = viewOf(
            accounts = listOf(account("w1", "1000")),
            tokens = mapOf("w1" to tokens),
            prices = mapOf(MON to price(1.0)) + tokens.associate { it.assetId to price(1.0) },
        )
        val state = ready(view)
        assertEquals(11, state.slices.size)
        val shares = AllocationMath.shares(state.slices, AllocationPalette.otherOf(DARK))
        assertEquals(listOf(MON_ID, AllocationMath.OTHER_ID), shares.map { it.assetId })
        assertEquals(1000f / 1010f, shares[0].fraction, 1e-6f)
        assertEquals(10f / 1010f, shares[1].fraction, 1e-6f)
    }

    /** The ready state of [view], which the test expects to be ready. */
    private fun ready(view: WalletsView): AllocationState.Ready =
        WalletsAllocation.state(view, shown = true, background = DARK) as AllocationState.Ready

    private fun viewOf(
        accounts: List<WalletAccount> = emptyList(),
        tokens: Map<String, List<TokenAsset>> = emptyMap(),
        prices: Map<AssetId, Price> = emptyMap(),
    ): WalletsView = WalletsView(emptyList(), accounts.groupBy { it.walletId }, tokens, prices)

    private fun account(walletId: String, coins: String): WalletAccount = WalletAccount(
        walletId = walletId,
        chain = AccountChain.ETHEREUM,
        index = 0,
        address = ADDRESS,
        networkName = "Monad Testnet",
        balance = NativeAsset(id = MON_ID, symbol = "MON", name = "Monad Testnet", network = MONAD, balance = BigDecimal(coins)),
    )

    private fun usdc(amount: String): TokenAsset = tokenOf(USDC_CONTRACT, amount)

    private fun tokenOf(contract: String, amount: String): TokenAsset = TokenAsset(
        id = TokenAsset.idOf(MONAD, contract),
        symbol = "USDC",
        name = "Monad Testnet",
        network = MONAD,
        contract = contract,
        balance = BigDecimal(amount),
    )

    private fun price(usd: Double): Price = Price(usd = usd, change24hPct = null, asOfMs = 0L, stale = false)

    private companion object {
        val DARK = Color(0xFF0B0D12)
        val MONAD = NetworkId(Family.EVM, "10143", testnet = true)
        val MON = AssetId.Native(MONAD)
        val MON_ID = NativeAsset.idOf(MONAD)
        const val ADDRESS = "0x" + "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        val USDC_CONTRACT = "0x" + "c3".repeat(20)
        val USDC = AssetId.Erc20.of(MONAD, USDC_CONTRACT)
        val USDC_ID = TokenAsset.idOf(MONAD, USDC_CONTRACT)
    }
}
