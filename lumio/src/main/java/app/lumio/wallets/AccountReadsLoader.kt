package app.lumio.wallets

import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.bitcoinAccountRef
import app.lumio.chains.ChainHolding
import app.lumio.chains.ChainHoldings
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenHolding
import app.lumio.tokens.TokenPortfolio
import app.lumio.transactions.TxFeedItem
import app.lumio.transactions.TxSyncPlan
import app.lumio.transactions.accountPage

/** How many of an account's newest transactions its screen shows. */
const val ACTIVITY_ROWS = 10

/**
 * What an account's screen reads beyond its balance: its ERC-20 tokens, its SPL tokens or Algorand assets, the addresses its activity is read for, and the
 * rows of that activity. The wallet board reads them as the vault opens and refreshes, so the Account screen opens on them. Call the functions off the
 * main thread; they read the vault's settings and caches, and the holdings read the network.
 */
internal object AccountReadsLoader {
    /** The enabled chains of [mode]'s side, as the account reads them. */
    fun chainsOf(app: LumioApp, session: VaultSession, mode: NetworkMode): List<EvmChain> {
        val settings = EvmSettingsStore.read(session)
        return EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
    }

    /** The ERC-20 holdings of an Ethereum account on [mode]'s chains that hold something, from the vault's caches. Empty for the other chains. */
    fun tokensOf(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): List<TokenHolding> {
        if (account.namespace != Networks.EIP155) return emptyList()
        return TokenPortfolio.holdingsAt(
            account.walletId,
            account.address,
            chainsOf(app, session, mode),
            app.tokensFor(session),
            TokenBalanceCache.read(session),
        ).filter { it.amount.signum() > 0 }
    }

    /**
     * The SPL tokens of a Solana account, or the assets of an Algorand account, that its address holds on [mode]'s network. Other chains have none. It reads
     * the network, and throws when the read fails.
     */
    suspend fun holdingsOf(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): List<ChainHolding> {
        val scheduler = app.nativeSchedulerFor(session)
        return when (account.namespace) {
            Networks.SOLANA -> {
                val network = if (mode == NetworkMode.TESTNET) SOLANA_DEVNET else SOLANA_MAINNET
                ChainHoldings.solana(scheduler, network, account.address, ChainHoldings.solanaNames(app), account.networkName)
            }
            Networks.ALGORAND -> {
                val network = if (mode.holds(ALGORAND_TESTNET.testnet)) ALGORAND_TESTNET else ALGORAND_MAINNET
                ChainHoldings.algorand(scheduler, network, account.address, account.networkName)
            }
            else -> emptyList()
        }
    }

    /** The reads that the activity of [account] makes on [mode]'s side: its address on each enabled network, or its Bitcoin account's scan. */
    fun planOf(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): TxSyncPlan {
        val bitcoin = if (account.namespace == Networks.BIP122) bitcoinAccountRef(session, account.walletId, account.index, mode) else null
        return AccountActivity.plan(account, chainsOf(app, session, mode), bitcoin)
    }

    /**
     * The addresses that the activity of [account] is read for: its Ethereum address, or the addresses that the last scan of its Bitcoin account found in
     * use. Empty for the other chains, and for a Bitcoin account that has no scan yet.
     */
    suspend fun addressesOf(vault: LumioVault, session: VaultSession, mode: NetworkMode, account: WalletAccount): Set<String> = when (account.namespace) {
        Networks.EIP155 -> setOf(account.address)
        Networks.BIP122 -> bitcoinAccountRef(session, account.walletId, account.index, mode)
            ?.let { vault.store.snapshot(it.key)?.addresses?.toSet() }
            .orEmpty()
        else -> emptySet()
    }

    /** The newest [ACTIVITY_ROWS] rows of [addresses] on [networks] from the vault's store, each with its comment. It reads no network. */
    suspend fun activityOf(app: LumioApp, vault: LumioVault, addresses: Set<String>, networks: Set<NetworkId>): List<TxFeedItem> =
        accountPage(app, vault, addresses, networks, ACTIVITY_ROWS).items
}
