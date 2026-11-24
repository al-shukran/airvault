package app.lumio.wallets

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.btc.BtcAccountRef
import app.lumio.chains.NativeBalanceCache
import app.lumio.chains.NativeBalances
import app.lumio.chains.NativeFailure
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.evm.BalanceCache
import app.lumio.evm.ChainFailure
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.home.HomeAssets
import app.lumio.home.HomeLoader
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.portfolio.PortfolioTotal
import app.lumio.portfolio.PortfolioView
import app.lumio.portfolio.holdingOf
import app.lumio.rowsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * The wallets of the open vault on one side, each with its accounts, and the prices of the balances those accounts show. A wallet with no account on
 * the side has an empty list.
 */
class WalletsView(
    val wallets: List<PairedWallet>,
    private val accounts: Map<String, List<WalletAccount>>,
    private val prices: Map<AssetId, Price>,
) {
    /** The accounts of the wallet [walletId] on the side, in chain order. */
    fun accountsOf(walletId: String): List<WalletAccount> = accounts[walletId].orEmpty()

    /** The value of the wallet [walletId] in dollars and its change over 24 hours, counted as the Portfolio counts its holdings. */
    fun total(walletId: String): PortfolioTotal = PortfolioView.total(HomeAssets.priced(balancesOf(walletId), prices).map { holdingOf(it) })

    /** The wallets with the wallet [activeId] first, then the rest in the vault's order. */
    fun walletsActiveFirst(activeId: String?): List<PairedWallet> = wallets.sortedBy { it.id != activeId }

    /** The value in dollars of [account]'s native coin, or null when that coin has no price or no balance is kept. */
    fun usd(account: WalletAccount): Double? = HomeAssets.totalUsd(HomeAssets.priced(listOfNotNull(account.balance), prices))

    /** The time of the oldest price kept from an earlier read, among the balances of the side, or null when every price is from this read. */
    fun staleSinceMs(): Long? = HomeAssets.staleSinceMs(accounts.values.flatten().mapNotNull { it.balance }, prices)

    private fun balancesOf(walletId: String) = accountsOf(walletId).mapNotNull { it.balance }
}

/**
 * What a refresh did not get: the chains whose read did not answer, and whether a read failed as a whole, so that no chain can be named for it.
 */
data class RefreshResult(val unreachable: Set<ChainId>, val unreadable: Boolean) {
    /** True when any read did not answer. */
    val failed: Boolean get() = unreadable || unreachable.isNotEmpty()
}

/** Reads the wallets' accounts from the vault, and refreshes their balances from the networks. */
internal object WalletsLoader {
    /**
     * The wallets of the open vault and their accounts on [mode]'s side, read from the vault's caches, with the last prices kept. It reads nothing from a
     * source, so the accounts show at once. It runs on the I/O dispatcher, so the board's main-thread callers do not wait for it.
     */
    suspend fun read(app: LumioApp, session: VaultSession, mode: NetworkMode): WalletsView = withContext(Dispatchers.IO) {
        val wallets = PairedWallets.list(session)
        val accounts = accountsOn(app, session, mode, wallets)
        val prices = HomeLoader.lastPrices(app, accounts.values.flatten().mapNotNull { it.balance })
        WalletsView(wallets, accounts, prices)
    }

    /**
     * Reads the chain families [families] of [mode]'s side, all at once: the Ethereum balances, the Algorand and Solana balances, and the Bitcoin scans.
     * [landed] runs as each read ends, with the namespaces it covers and the chains that did not answer, so the caller shows its result without waiting
     * for the others. A read that failed keeps its cached balance, and the result names the chains that did not answer.
     */
    suspend fun refresh(
        app: LumioApp,
        session: VaultSession,
        mode: NetworkMode,
        families: Set<String>,
        landed: suspend (namespaces: Set<String>, unreachable: Set<ChainId>) -> Unit,
    ): RefreshResult = refreshChains(
        evm = if (Networks.EIP155 in families) ({ HomeLoader.refreshEvm(app, session, mode) }) else null,
        native = if (Networks.SOLANA in families || Networks.ALGORAND in families) ({ HomeLoader.refreshNative(app, session, mode) }) else null,
        bitcoin = if (Networks.BIP122 in families) ({ HomeLoader.scanBitcoin(app, session, mode) }) else null,
        landed = landed,
    )

    /**
     * The account [id] of the wallet [walletId] on [mode]'s side, with the wallet's name, or null when the vault has no such account. It reads only that
     * wallet and no price, so a receive opens without reading the other wallets or the network.
     */
    suspend fun account(
        app: LumioApp,
        session: VaultSession,
        mode: NetworkMode,
        walletId: String,
        id: AccountId,
    ): Pair<String, WalletAccount>? {
        val wallet = withContext(Dispatchers.IO) { PairedWallets.get(session, walletId) } ?: return null
        val account = accountsOn(app, session, mode, listOf(wallet))[walletId].orEmpty()
            .firstOrNull { it.id == id } ?: return null
        return wallet.name to account
    }

    /** The accounts of [wallets] on [mode]'s side, from the open vault. Runs off the main thread. */
    private suspend fun accountsOn(
        app: LumioApp,
        session: VaultSession,
        mode: NetworkMode,
        wallets: List<PairedWallet>,
    ): Map<String, List<WalletAccount>> = withContext(Dispatchers.IO) {
        val settings = EvmSettingsStore.read(session)
        val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
        val evmCache = BalanceCache.read(session)
        val nativeCache = NativeBalanceCache.read(session)
        val coins = NativeBalances.coinsOf(mode)
        val bitcoin = rowsOf(app.lumioVault(), wallets.flatMap { wallet -> wallet.bitcoin.map { BtcAccountRef(wallet.id, it) } })
        wallets.associate { wallet ->
            wallet.id to WalletAccounts.of(wallet, mode, chains, evmCache, settings.addressCount, bitcoin, coins, nativeCache)
        }
    }
}

/** [block], or null when it fails. A cancellation is passed on, so a screen that leaves stops its reads. */
internal suspend fun <T> readOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * Runs the reads that are given, at once. A null read is not run. [landed] runs as each read ends, whether it answered or not, with the namespaces the
 * read covers and the chains that did not answer, so the caller shows its result at once. A read that fails is a failure of its own chain only: the
 * other reads still run to the end.
 */
internal suspend fun refreshChains(
    evm: (suspend () -> List<ChainFailure>)?,
    native: (suspend () -> List<NativeFailure>)?,
    bitcoin: (suspend () -> Unit)?,
    landed: suspend (namespaces: Set<String>, unreachable: Set<ChainId>) -> Unit,
): RefreshResult = supervisorScope {
    val evmRead = evm?.let { read ->
        async {
            readOrNull(read).also { failures -> landed(setOf(Networks.EIP155), failures.orEmpty().map { Networks.evm(it.chainId) }.toSet()) }
        }
    }
    val nativeRead = native?.let { read ->
        async {
            readOrNull(read).also { failures ->
                landed(setOf(Networks.SOLANA, Networks.ALGORAND), failures.orEmpty().map { it.network.chain }.toSet())
            }
        }
    }
    bitcoin?.let { read -> launch { readOrNull(read); landed(setOf(Networks.BIP122), emptySet()) } }
    val evmFailures = evmRead?.await()
    val nativeFailures = nativeRead?.await()
    RefreshResult(
        unreachable = (evmFailures.orEmpty().map { Networks.evm(it.chainId) } + nativeFailures.orEmpty().map { it.network.chain }).toSet(),
        unreadable = (evmRead != null && evmFailures == null) || (nativeRead != null && nativeFailures == null),
    )
}
