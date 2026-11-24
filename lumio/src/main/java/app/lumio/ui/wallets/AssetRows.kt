package app.lumio.ui.wallets

import app.lensly.core.registry.Amounts
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lumio.chains.ChainHolding
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.data.tx.LumioTx
import app.lumio.home.HomeFormat
import app.lumio.home.NativeAsset
import app.lumio.send.algorand.VERIFIED_ASSETS
import app.lumio.tokens.TokenHolding
import app.lumio.transactions.evmNetwork
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.letterOf
import app.lumio.ui.home.tokenIcon
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal

/** The key of an account's native coin in its assets, and in the asset route. A token's key is its lowercase contract. */
const val NATIVE_ASSET_KEY = "native"

/**
 * One row of an account's assets (section 2.1, Account): a coin or token that the account holds. [assetKey] names it in the asset route, and [symbol]
 * titles its row. [name] is its full name and [network] the network it sits on. [amountText] is the amount in whole units, and [usd] its value in
 * dollars, null while no price is known. [assetId] is what its price is of, null when no price source names it. [testnet] marks a testnet coin, and
 * [zero] a coin of nothing.
 */
internal data class AssetRowModel(
    val assetKey: String,
    val symbol: String,
    val name: String,
    val network: String,
    val amountText: String,
    val usd: Double?,
    val icon: AssetIconSpec,
    val assetId: AssetId?,
    val isNative: Boolean,
    val testnet: Boolean,
    val zero: Boolean,
)

/**
 * The rows of an account's assets, and the rules that key them and price them. The registry lookups are passed in, so the rules run without it.
 */
internal object AssetRows {
    /**
     * The assets of [account]: its native coin when a read of it is kept, then its ERC-20 [tokens], then its SPL tokens or Algorand assets [holdings].
     * A row whose price is in [prices] carries its value in dollars. [network] and [token] look up the logos in the registry.
     */
    fun of(
        account: WalletAccount,
        tokens: List<TokenHolding>,
        holdings: List<ChainHolding>,
        prices: Map<AssetId, Price>,
        network: (Long) -> NetworkEntry?,
        token: (Long, String) -> TokenEntry?,
    ): List<AssetRowModel> {
        val native = account.balance?.let { nativeRow(it, account.networkName, prices, network) }
        return listOfNotNull(native) + tokens.map { tokenRow(it, prices, network, token) } + holdings.map(::holdingRow)
    }

    /**
     * The row of the Algorand asset [assetKey] for [account], when the account has no row for it: an asset it holds nothing of, or has not opted in to.
     * Search opens such an asset's page, which has no balance and no price, and Receive on it offers the opt-in. Null unless [account] is an Algorand
     * account and [assetKey] is an asset ID.
     */
    fun unheldAlgorandRow(account: WalletAccount, assetKey: String): AssetRowModel? {
        val assetId = assetKey.toULongOrNull()?.takeIf { it > 0UL } ?: return null
        if (account.namespace != Networks.ALGORAND) return null
        val testnet = account.id.chainId == Networks.ALGO_TESTNET
        val symbol = VERIFIED_ASSETS.firstOrNull { it.assetId == assetId && it.testnet == testnet }?.symbol ?: "ASA $assetId"
        return AssetRowModel(
            assetKey = assetKey,
            symbol = symbol,
            name = symbol,
            network = account.networkName,
            amountText = "0",
            usd = null,
            icon = AssetIconSpec.Letter(letterOf(symbol)),
            assetId = null,
            isNative = false,
            testnet = testnet,
            zero = true,
        )
    }

    /** The prices that [account] and its [tokens] can have, the native coin first. */
    fun pricedIds(account: WalletAccount, tokens: List<TokenHolding>): List<AssetId> {
        val native = account.balance?.let { AssetId.Native(it.network) }
        return (listOfNotNull(native) + tokens.mapNotNull(::erc20IdOf)).distinct()
    }

    /** The prices of the tokens [holdings] hold, which their dollar values need. */
    fun tokenIds(holdings: List<TokenHolding>): List<AssetId> = holdings.mapNotNull(::erc20IdOf).distinct()

    /** The dollar value of the [holdings] that [prices] gives a price, or null when none of them has one. */
    fun tokensUsd(holdings: List<TokenHolding>, prices: Map<AssetId, Price>): Double? = holdings.mapNotNull { holding ->
        val price = erc20IdOf(holding)?.let { prices[it] } ?: return@mapNotNull null
        BigDecimal(holding.amount).movePointLeft(holding.token.decimals).toDouble() * price.usd
    }.takeIf { it.isNotEmpty() }?.sum()

    /** The key of the asset that [tx] moves: its lowercase contract, or [NATIVE_ASSET_KEY] for the native coin. */
    fun assetKeyOf(tx: LumioTx): String = tx.asset.contract?.lowercase() ?: NATIVE_ASSET_KEY

    private fun nativeRow(
        balance: NativeAsset,
        networkName: String,
        prices: Map<AssetId, Price>,
        network: (Long) -> NetworkEntry?,
    ): AssetRowModel {
        val id = AssetId.Native(balance.network)
        val price = prices[id]
        return AssetRowModel(
            assetKey = NATIVE_ASSET_KEY,
            symbol = balance.symbol,
            name = balance.name,
            network = networkName,
            amountText = HomeFormat.coins(balance.balance),
            usd = price?.let { balance.balance.toDouble() * it.usd },
            icon = nativeIcon(balance.network, balance.symbol, network),
            assetId = id,
            isNative = true,
            testnet = balance.testnet,
            zero = balance.balance.signum() == 0,
        )
    }

    /** The logo of the native coin [symbol] on [network]: the Bitcoin mark, the chain's logo on Ethereum, or the coin's letter elsewhere. */
    fun nativeIcon(network: NetworkId, symbol: String, lookup: (Long) -> NetworkEntry?): AssetIconSpec = when {
        network.isBtc -> AssetIconSpec.Bitcoin
        network.isEvm -> AssetIconSpec.Chain(network.name.toLongOrNull()?.let(lookup))
        else -> AssetIconSpec.Letter(letterOf(symbol))
    }

    private fun tokenRow(
        holding: TokenHolding,
        prices: Map<AssetId, Price>,
        network: (Long) -> NetworkEntry?,
        token: (Long, String) -> TokenEntry?,
    ): AssetRowModel {
        val chainId = holding.chain.chainId
        val contract = holding.token.contract.lowercase()
        val decimals = holding.token.decimals
        val id = erc20IdOf(holding)
        val price = id?.let { prices[it] }
        return AssetRowModel(
            assetKey = contract,
            symbol = holding.token.symbol,
            name = holding.token.name,
            network = holding.chain.name,
            amountText = Amounts.format(holding.amount, decimals),
            usd = price?.let { BigDecimal(holding.amount).movePointLeft(decimals).toDouble() * it.usd },
            icon = tokenIcon(token(chainId, contract), network(chainId), holding.token.symbol),
            assetId = id,
            isNative = false,
            testnet = holding.chain.testnet,
            zero = false,
        )
    }

    private fun holdingRow(holding: ChainHolding): AssetRowModel = AssetRowModel(
        assetKey = holding.key,
        symbol = holding.symbol,
        name = holding.symbol,
        network = holding.network,
        amountText = Amounts.format(holding.amount, holding.decimals),
        usd = null,
        icon = AssetIconSpec.Letter(letterOf(holding.symbol)),
        assetId = null,
        isNative = false,
        testnet = false,
        zero = false,
    )

    private fun erc20IdOf(holding: TokenHolding): AssetId? =
        runCatching { AssetId.Erc20.of(evmNetwork(holding.chain), holding.token.contract) }.getOrNull()
}
