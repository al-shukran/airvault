package app.lumio.home

import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.bitcoinRefs
import app.lumio.btc.BtcRow
import app.lumio.btc.networkId
import app.lumio.data.asset.AssetId
import app.lumio.data.price.Price
import app.lumio.data.sched.Priority
import app.lumio.demo.DemoPortfolio
import app.lumio.evm.BalanceCache
import app.lumio.evm.ChainFailure
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.evm.Portfolio
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallets
import app.lumio.queueScans
import app.lumio.rowsOf
import app.lumio.tokens.CustomTokenStore
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenPortfolio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home's reads. Each step reads one source and returns what it read, so Home adds it to the list at once: the cached balances first, then the
 * prices, the Ethereum balances and the Bitcoin scans. Every vault read and every database read runs off the main thread.
 */
internal object HomeLoader {
    /**
     * The balances of [mode]'s side from the vault, native and token: the balance cache for Ethereum, the token cache, and the last scans for Bitcoin.
     * It reads no network.
     */
    suspend fun cached(app: LumioApp, session: VaultSession, mode: NetworkMode): List<HomeAsset> = withContext(Dispatchers.IO) {
        DemoPortfolio.homeAssets(mode)?.let { return@withContext it }
        val settings = EvmSettingsStore.read(session)
        val chains = evmChains(app, settings, mode)
        val wallets = PairedWallets.list(session)
        val holdings = Portfolio.holdings(wallets, settings.addressCount, chains, BalanceCache.read(session))
        val tokensOf = app.tokensFor(session)
        val tokenCache = TokenBalanceCache.read(session)
        val tokens = wallets.flatMap { TokenPortfolio.holdings(it, settings.addressCount, chains, tokensOf, tokenCache) }
        val owned = CustomTokenStore.read(session)
        HomeBalances.evm(chains, holdings) + HomeBalances.bitcoin(bitcoinRows(app, session, mode)) +
            HomeBalances.tokens(tokens, isKnown = app::isKnownToken, isCustom = { network, contract -> CustomTokenStore.holds(owned, network, contract) })
    }

    /** The prices of the coins of [assets], keyed by asset. An asset with no price, or whose sources do not answer, is absent. */
    suspend fun prices(app: LumioApp, assets: List<HomeAsset>): Map<AssetId, Price> = withContext(Dispatchers.IO) {
        app.prices.assetPrices(assets.map { it.assetId }.distinct())
    }

    /**
     * Reads the enabled Ethereum chains of [mode]'s side for every paired wallet, and keeps the reads in the balance cache, then reads the tokens of
     * each wallet on those chains. Returns the chains that did not answer. Their cached reads stay as they were.
     */
    suspend fun refreshEvm(app: LumioApp, session: VaultSession, mode: NetworkMode): List<ChainFailure> = withContext(Dispatchers.IO) {
        if (DemoPortfolio.isOn()) return@withContext emptyList()
        val settings = EvmSettingsStore.read(session)
        val chains = evmChains(app, settings, mode)
        val wallets = PairedWallets.list(session).filter { it.evmKeys.isNotEmpty() }
        if (chains.isEmpty() || wallets.isEmpty()) {
            emptyList()
        } else {
            val reads = app.evmReadsFor(session)
            val failures = Portfolio.refresh(session, wallets, settings, chains, reads).values.flatten()
            // A token that fails keeps its earlier read, so the native result stands on its own.
            val tokensOf = app.tokensFor(session)
            for (wallet in wallets) TokenPortfolio.refresh(session, wallet, settings, chains, tokensOf, reads)
            failures
        }
    }

    /**
     * Queues a scan of each Bitcoin account of [mode]'s side, and runs the scans. The scan queue reports each account as it finishes, so Home can
     * read the scans as they land.
     */
    suspend fun scanBitcoin(app: LumioApp, session: VaultSession, mode: NetworkMode) {
        if (DemoPortfolio.isOn()) return
        val refs = withContext(Dispatchers.IO) { bitcoinRefs(session).filter { mode.holds(it.account.network) } }
        if (refs.isEmpty()) return
        val net = mode.btcNetwork().networkId()
        withContext(Dispatchers.IO) { queueScans(app.scanQueue, refs, net, Priority.VISIBLE) }
        app.startScan(net).join()
    }

    /** The chains of [mode]'s side that the owner has turned on. The registry is read on first use, so the caller runs this off the main thread. */
    private fun evmChains(app: LumioApp, settings: EvmSettings, mode: NetworkMode): List<EvmChain> =
        EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }

    /** The Bitcoin rows of [mode]'s side, each with its last scan. */
    private suspend fun bitcoinRows(app: LumioApp, session: VaultSession, mode: NetworkMode): List<BtcRow> =
        rowsOf(app.lumioVault(), bitcoinRefs(session).filter { mode.holds(it.account.network) })
}
