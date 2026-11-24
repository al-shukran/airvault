package app.lumio.home

import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.bitcoinRefs
import app.lumio.btc.networkId
import app.lumio.chains.NativeBalances
import app.lumio.chains.NativeFailure
import app.lumio.data.asset.AssetId
import app.lumio.data.price.Price
import app.lumio.data.sched.Priority
import app.lumio.evm.ChainFailure
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.evm.Portfolio
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallets
import app.lumio.queueScans
import app.lumio.tokens.TokenPortfolio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The wallet screens' reads. Each step reads one source and returns what it read: the prices, the Ethereum balances and tokens, the native balances, and
 * the Bitcoin scans. Every vault read and every database read runs off the main thread.
 */
internal object HomeLoader {
    /** The last prices kept of the coins of [assets], keyed by asset. It asks no source, so an asset that no read has priced is absent. */
    suspend fun lastPrices(app: LumioApp, assets: List<HomeAsset>): Map<AssetId, Price> = withContext(Dispatchers.IO) {
        app.prices.lastPrices(assets.map { it.assetId }.distinct())
    }

    /**
     * Reads the enabled Ethereum chains of [mode]'s side for every paired wallet, and keeps the reads in the balance cache, then reads the tokens of
     * each wallet on those chains. Returns the chains that did not answer. Their cached reads stay as they were.
     */
    suspend fun refreshEvm(app: LumioApp, session: VaultSession, mode: NetworkMode): List<ChainFailure> = withContext(Dispatchers.IO) {
        val settings = EvmSettingsStore.read(session)
        val chains = evmChains(app, settings, mode)
        val wallets = PairedWallets.list(session).filter { it.evmAddresses.isNotEmpty() }
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
     * Reads the Algorand and Solana balances of [mode]'s side for every paired wallet, and keeps the reads in the balance cache. Returns the reads that did
     * not answer. Their cached balances stay as they were.
     */
    suspend fun refreshNative(app: LumioApp, session: VaultSession, mode: NetworkMode): List<NativeFailure> = withContext(Dispatchers.IO) {
        val coins = NativeBalances.coinsOf(mode)
        val wallets = PairedWallets.list(session).filter { it.algorand.isNotEmpty() || it.solana.isNotEmpty() }
        if (coins.isEmpty() || wallets.isEmpty()) {
            emptyList()
        } else {
            NativeBalances.refresh(session, wallets, coins, app.nativeSchedulerFor(session))
        }
    }

    /**
     * Queues a scan of each Bitcoin account of [mode]'s side, and runs the scans. The first account is the one at the top of the wallet screen, so it
     * scans at FOREGROUND and its recent transactions are read first. The scan queue reports each account as it finishes, so Home can read the scans
     * as they land.
     */
    suspend fun scanBitcoin(app: LumioApp, session: VaultSession, mode: NetworkMode) {
        val refs = withContext(Dispatchers.IO) { bitcoinRefs(session).filter { mode.holds(it.account.network) } }
        if (refs.isEmpty()) return
        val net = mode.btcNetwork().networkId()
        withContext(Dispatchers.IO) {
            queueScans(app.scanQueue, refs.take(1), net, Priority.FOREGROUND)
            queueScans(app.scanQueue, refs.drop(1), net, Priority.VISIBLE)
        }
        app.startScan(net).join()
    }

    /** The chains of [mode]'s side that the owner has turned on. The registry is read on first use, so the caller runs this off the main thread. */
    private fun evmChains(app: LumioApp, settings: EvmSettings, mode: NetworkMode): List<EvmChain> =
        EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
}
