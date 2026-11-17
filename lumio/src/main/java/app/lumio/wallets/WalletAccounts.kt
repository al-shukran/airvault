package app.lumio.wallets

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lumio.btc.BtcRow
import app.lumio.chains.CachedNativeBalance
import app.lumio.chains.NativeCoin
import app.lumio.data.net.Family
import app.lumio.evm.CachedBalance
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.Holding
import app.lumio.home.HomeBalances
import app.lumio.home.NativeAsset
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import java.math.BigInteger
import java.util.Locale

/** The chains a wallet keeps accounts on, in the order the Wallet screen lists them. */
enum class AccountChain(val label: String) {
    BITCOIN("Bitcoin"),
    ETHEREUM("Ethereum"),
    SOLANA("Solana"),
    ALGORAND("Algorand");

    /** The name of the chain in a route, such as "ethereum". */
    val route: String get() = name.lowercase(Locale.ROOT)

    companion object {
        /** The chain whose [route] is [route], or null when no chain has it. */
        fun ofRoute(route: String): AccountChain? = entries.firstOrNull { it.route == route }
    }
}

/**
 * One account of a paired wallet on one chain, on the side the owner chose. [index] is its place in the wallet's list for that chain, and it names
 * the account in the routes. [address] is the address the account receives at. [networkName] names the network that [balance] is read on, and
 * [balance] is that network's native coin, or null while no read of it is kept. [script] is the script type of a Bitcoin account.
 */
data class WalletAccount(
    val walletId: String,
    val chain: AccountChain,
    val index: Int,
    val address: String,
    val networkName: String,
    val balance: NativeAsset?,
    val script: String? = null,
)

/**
 * The accounts of paired wallets, each with the balance of the network that the side shows for it. A side shows its own networks only: Bitcoin
 * accounts on the side's Bitcoin network, an Ethereum account's native coin on the side's primary network, and each Algorand or Solana account on
 * the side's coin. Every read comes from the vault's caches, so the mapping reads no network.
 */
object WalletAccounts {
    /** The network whose native coin an Ethereum account shows on [mode]: Ethereum on mainnet, and Monad testnet on testnet. */
    fun primaryEvmChainId(mode: NetworkMode): Long =
        if (mode == NetworkMode.TESTNET) EvmCatalog.PRIMARY_TESTNET_ID else EvmCatalog.PRIMARY_MAINNET_ID

    /**
     * The accounts of [wallet] on [mode]'s side, in chain order: Bitcoin, Ethereum, Solana, Algorand. [evmChains] are the Ethereum networks of the side
     * that are on, [evmCache] and [nativeCache] the balances the vault holds, and [addressCount] how many Ethereum addresses the wallet shows. [bitcoin]
     * holds the Bitcoin rows of the vault, and [coins] the Algorand and Solana coins of the side.
     */
    fun of(
        wallet: PairedWallet,
        mode: NetworkMode,
        evmChains: List<EvmChain>,
        evmCache: List<CachedBalance>,
        addressCount: Int,
        bitcoin: List<BtcRow>,
        coins: List<NativeCoin>,
        nativeCache: List<CachedNativeBalance>,
    ): List<WalletAccount> = bitcoinAccounts(wallet, mode, bitcoin.filter { it.ref.walletId == wallet.id }) +
        ethereumAccounts(wallet, mode, evmChains, evmCache, addressCount) +
        nativeAccounts(wallet, AccountChain.SOLANA, Family.SOL, wallet.solana, coins, nativeCache) { SolanaAccount.addressOf(it) } +
        nativeAccounts(wallet, AccountChain.ALGORAND, Family.ALGO, wallet.algorand, coins, nativeCache) { AlgorandAccount.addressOf(it) }

    /** The name of a Bitcoin script type as the screens show it, such as "native segwit". */
    fun scriptLabel(type: ScriptType): String = when (type) {
        ScriptType.LEGACY -> "legacy"
        ScriptType.NESTED_SEGWIT -> "nested segwit"
        ScriptType.NATIVE_SEGWIT -> "native segwit"
        ScriptType.TAPROOT -> "taproot"
    }

    /**
     * The Bitcoin accounts on [mode]'s side. Each one's address is its first receive address, m/.../0/0, the address Lensly shows for the same account,
     * so a scan never changes it. Its balance is its last scan. [WalletAccount.index] is the account's place in the wallet's list, so two accounts of one
     * network and Script Type keep their own rows and open their own pages.
     */
    private fun bitcoinAccounts(wallet: PairedWallet, mode: NetworkMode, rows: List<BtcRow>): List<WalletAccount> =
        rows.filter { mode.holds(it.ref.account.network) }.map { row ->
            val account = row.ref.account
            WalletAccount(
                walletId = wallet.id,
                chain = AccountChain.BITCOIN,
                index = wallet.bitcoin.indexOfFirst { it.path.path == account.path.path },
                address = account.receiveAddress(0),
                networkName = if (account.network == BtcNetwork.TESTNET4) "Bitcoin testnet4" else "Bitcoin",
                balance = HomeBalances.bitcoin(listOf(row)).singleOrNull(),
                script = scriptLabel(account.scriptType),
            )
        }

    /** The Ethereum accounts that [addressCount] shows, each with its native coin on the primary network of [mode]'s side. */
    private fun ethereumAccounts(
        wallet: PairedWallet,
        mode: NetworkMode,
        chains: List<EvmChain>,
        cache: List<CachedBalance>,
        addressCount: Int,
    ): List<WalletAccount> {
        val chainId = primaryEvmChainId(mode)
        val primary = chains.firstOrNull { it.chainId == chainId }
        return wallet.evmAccounts.take(addressCount).mapIndexed { index, account ->
            val address = account.address
            val read = cache.firstOrNull { it.walletId == wallet.id && it.chainId == chainId && it.address == address }
            val balance = if (primary != null && read != null) {
                HomeBalances.evm(listOf(primary), listOf(Holding(primary, BigInteger(read.wei), read.atMs))).singleOrNull()
            } else {
                null
            }
            WalletAccount(
                walletId = wallet.id,
                chain = AccountChain.ETHEREUM,
                index = index,
                address = address,
                networkName = primary?.name ?: AccountChain.ETHEREUM.label,
                balance = balance,
            )
        }
    }

    /**
     * The accounts of one Algorand or Solana family: each key is one account, and its balance is the family's coin on [mode]'s side. A side with no
     * coin for the family shows the account with no balance.
     */
    private fun nativeAccounts(
        wallet: PairedWallet,
        chain: AccountChain,
        family: Family,
        keys: List<ByteArray>,
        coins: List<NativeCoin>,
        cache: List<CachedNativeBalance>,
        addressOf: (ByteArray) -> String,
    ): List<WalletAccount> {
        val coin = coins.firstOrNull { it.network.family == family }
        return keys.mapIndexed { index, key ->
            // The coin reads the one account of its family, so the wallet holding only this key gives this account's balance.
            val alone = if (family == Family.SOL) {
                wallet.copy(solana = listOf(key), algorand = emptyList())
            } else {
                wallet.copy(solana = emptyList(), algorand = listOf(key))
            }
            WalletAccount(
                walletId = wallet.id,
                chain = chain,
                index = index,
                address = addressOf(key),
                networkName = coin?.name ?: chain.label,
                balance = coin?.let { HomeBalances.nativeCoins(listOf(alone), cache, listOf(it)).singleOrNull() },
            )
        }
    }
}
