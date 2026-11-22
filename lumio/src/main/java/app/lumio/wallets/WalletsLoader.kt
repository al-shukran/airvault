package app.lumio.wallets

import app.airvault.core.model.AccountId
import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.btc.BtcAccountRef
import app.lumio.chains.NativeBalanceCache
import app.lumio.chains.NativeBalances
import app.lumio.data.asset.AssetId
import app.lumio.data.price.Price
import app.lumio.evm.BalanceCache
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.home.HomeAssets
import app.lumio.home.HomeLoader
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.rowsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    /** The value in dollars of the priced balances of the wallet [walletId], or null when none of them has a price. */
    fun usd(walletId: String): Double? = HomeAssets.totalUsd(HomeAssets.priced(balancesOf(walletId), prices))

    /** The value in dollars of [account]'s native coin, or null when that coin has no price or no balance is kept. */
    fun usd(account: WalletAccount): Double? = HomeAssets.totalUsd(HomeAssets.priced(listOfNotNull(account.balance), prices))

    /** The time of the oldest price kept from an earlier read, among the balances of the side, or null when every price is from this read. */
    fun staleSinceMs(): Long? = HomeAssets.staleSinceMs(accounts.values.flatten().mapNotNull { it.balance }, prices)

    private fun balancesOf(walletId: String) = accountsOf(walletId).mapNotNull { it.balance }
}

/** Reads the wallets' accounts from the vault, and refreshes their balances from the networks. */
internal object WalletsLoader {
    /**
     * The wallets of the open vault and their accounts on [mode]'s side, read from the vault's caches. The prices are read after the accounts, so the
     * accounts show at once. It reads no balance from the network.
     */
    suspend fun read(app: LumioApp, session: VaultSession, mode: NetworkMode): WalletsView {
        val wallets = withContext(Dispatchers.IO) { PairedWallets.list(session) }
        val accounts = accountsOn(app, session, mode, wallets)
        val prices = HomeLoader.prices(app, accounts.values.flatten().mapNotNull { it.balance })
        return WalletsView(wallets, accounts, prices)
    }

    /**
     * Reads the Ethereum, Algorand and Solana balances of [mode]'s side, and scans its Bitcoin accounts. Returns true when a read did not answer. A read
     * that failed keeps its cached balance.
     */
    suspend fun refresh(app: LumioApp, session: VaultSession, mode: NetworkMode): Boolean {
        val ethereum = readOrNull { HomeLoader.refreshEvm(app, session, mode) }
        val native = readOrNull { HomeLoader.refreshNative(app, session, mode) }
        readOrNull { HomeLoader.scanBitcoin(app, session, mode) }
        return ethereum == null || ethereum.isNotEmpty() || native == null || native.isNotEmpty()
    }

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
