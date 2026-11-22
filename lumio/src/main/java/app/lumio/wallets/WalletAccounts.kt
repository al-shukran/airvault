package app.lumio.wallets

import app.airvault.chains.algorand.wallet.AlgorandWallet
import app.airvault.chains.solana.wallet.SolanaWallet
import app.airvault.core.model.AccountId
import app.airvault.core.model.AddressKind
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.btc.BtcRow
import app.lumio.btc.networkId
import app.lumio.chains.CachedNativeBalance
import app.lumio.chains.NativeCoin
import app.lumio.data.net.Networks
import app.lumio.evm.CachedBalance
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.Holding
import app.lumio.home.HomeBalances
import app.lumio.home.NativeAsset
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import java.math.BigInteger

/**
 * One account of a paired wallet on one chain family, on the side the owner chose. [id] is its CAIP-10 id: the network that [balance] is read on,
 * and the account's address. [walletId] and [id] name the account in the routes, and [index] is its place in the wallet's list for its family.
 * [networkName] names that network, and [balance] is its native coin, or null while no read of it is kept. [script] is the script type of a Bitcoin
 * account. [name], [label] and [group] are the names the owner gave the account in Lensly, and each is null when the pairing carries none.
 */
data class WalletAccount(
    val walletId: String,
    val id: AccountId,
    val index: Int,
    val networkName: String,
    val balance: NativeAsset?,
    val script: String? = null,
    val name: String? = null,
    val label: String? = null,
    val group: String? = null,
) {
    /** The address the account receives at: the address of [id]. */
    val address: String get() = id.address

    /** The CAIP-2 namespace of the account's chain: `bip122`, `eip155`, `solana` or `algorand`. It decides how each screen reads the account. */
    val namespace: String get() = id.chainId.namespace
}

/**
 * The accounts of paired wallets, each with the balance of the network that the side shows for it. A side shows its own networks only: Bitcoin
 * accounts on the side's Bitcoin network, an Ethereum account's native coin on the side's primary network, and each Algorand or Solana account on
 * the side's coin. Every read comes from the vault's caches, so the mapping reads no network.
 */
object WalletAccounts {
    /** The chain families a wallet keeps accounts on, by CAIP-2 namespace, in the order the Wallet screen lists them. */
    val FAMILIES: List<String> = listOf(Networks.BIP122, Networks.EIP155, Networks.SOLANA, Networks.ALGORAND)

    /** The name of a family as the screens show it: "Bitcoin", "Ethereum", "Solana" or "Algorand". */
    fun familyLabel(namespace: String): String = when (namespace) {
        Networks.BIP122 -> "Bitcoin"
        Networks.EIP155 -> "Ethereum"
        Networks.SOLANA -> "Solana"
        Networks.ALGORAND -> "Algorand"
        else -> error("Lumio shows no accounts on $namespace")
    }

    /** The network whose native coin an Ethereum account shows on [mode]: Ethereum on mainnet, and Monad testnet on testnet. */
    fun primaryEvmChainId(mode: NetworkMode): Long = EvmCatalog.primaryChainId(testnet = mode == NetworkMode.TESTNET)

    /**
     * The accounts of [wallet] on [mode]'s side, in family order: Bitcoin, Ethereum, Solana, Algorand. [evmChains] are the Ethereum networks of the side
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
        solanaAccounts(wallet, mode, coins, nativeCache) +
        algorandAccounts(wallet, mode, coins, nativeCache)

    /**
     * The index of the account [id] in [wallet]: the place of the key whose address is the id's address. The network part of the id is not checked,
     * so a route still finds its account on the other side. Null when the wallet holds no account at that address.
     */
    fun indexOf(wallet: PairedWallet, id: AccountId): Int? {
        val index = when (id.chainId.namespace) {
            Networks.BIP122 -> wallet.bitcoin.indexOfFirst { it.receiveAddress(0) == id.address }
            Networks.EIP155 -> wallet.evmAccounts.indexOfFirst { it.address == id.address }
            Networks.SOLANA -> wallet.solana.indexOfFirst { SolanaWallet.addresses.fromPublicKey(Networks.SOL_MAINNET, it) == id.address }
            Networks.ALGORAND -> wallet.algorand.indexOfFirst { AlgorandWallet.addresses.fromPublicKey(Networks.ALGO_MAINNET, it) == id.address }
            else -> -1
        }
        return index.takeIf { it >= 0 }
    }

    /** The name of a Bitcoin script type as the screens show it, such as "Native SegWit". */
    fun scriptLabel(type: ScriptType): String = when (type) {
        ScriptType.LEGACY -> "Legacy"
        ScriptType.NESTED_SEGWIT -> "Nested SegWit"
        ScriptType.NATIVE_SEGWIT -> "Native SegWit"
        ScriptType.TAPROOT -> "Taproot"
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
                id = AccountId.of(account.network.networkId().chain, account.receiveAddress(0)),
                index = wallet.bitcoin.indexOfFirst { it.path.path == account.path.path },
                networkName = if (account.network == BtcNetwork.TESTNET4) "Bitcoin Testnet4" else "Bitcoin",
                balance = HomeBalances.bitcoin(listOf(row)).singleOrNull(),
                script = scriptLabel(account.scriptType),
            )
        }

    /**
     * The Ethereum accounts that [addressCount] shows, each with its native coin on the primary network of [mode]'s side. A side whose primary network
     * is not in this build has no Ethereum accounts: it would show an Ethereum label with no balance.
     */
    private fun ethereumAccounts(
        wallet: PairedWallet,
        mode: NetworkMode,
        chains: List<EvmChain>,
        cache: List<CachedBalance>,
        addressCount: Int,
    ): List<WalletAccount> {
        val chainId = primaryEvmChainId(mode)
        val primary = chains.firstOrNull { it.chainId == chainId } ?: return emptyList()
        return wallet.evmAccounts.take(addressCount).mapIndexed { index, account ->
            val address = account.address
            val read = cache.firstOrNull { it.walletId == wallet.id && it.chainId == chainId && it.address == address }
            val balance = if (read != null) {
                HomeBalances.evm(listOf(primary), listOf(Holding(primary, BigInteger(read.wei), read.atMs))).singleOrNull()
            } else {
                null
            }
            WalletAccount(
                walletId = wallet.id,
                id = AccountId.of(Networks.evm(chainId), address),
                index = index,
                networkName = primary.name,
                balance = balance,
                name = account.name,
                label = account.label,
                group = account.group,
            )
        }
    }

    /**
     * The Solana accounts: each key is one account that [SolanaWallet.accountFrom] names on [mode]'s cluster, and its balance is the Solana coin on
     * that side. A side with no Solana coin shows the account with no balance.
     */
    private fun solanaAccounts(
        wallet: PairedWallet,
        mode: NetworkMode,
        coins: List<NativeCoin>,
        cache: List<CachedNativeBalance>,
    ): List<WalletAccount> {
        val network = if (mode == NetworkMode.TESTNET) Networks.SOL_DEVNET else Networks.SOL_MAINNET
        val coin = coins.firstOrNull { it.network.isSol }
        return wallet.solana.mapIndexed { index, key ->
            val export = AccountExport(
                chain = network,
                signer = SignerRef(wallet.fingerprint.toInt(), SolanaWallet.pathOf(index)),
                publicKey = PublicKey(Curve.ED25519_SLIP10, key),
                chainCode = null,
                kind = AddressKind.DEFAULT,
            )
            WalletAccount(
                walletId = wallet.id,
                id = SolanaWallet.accountFrom(export).single().id,
                index = index,
                networkName = coin?.name ?: familyLabel(Networks.SOLANA),
                balance = nativeBalance(wallet.copy(solana = listOf(key), algorand = emptyList()), coin, cache),
            )
        }
    }

    /**
     * The Algorand accounts: each key is one account that [AlgorandWallet.accountFrom] names on [mode]'s network, and its balance is the Algorand coin
     * on that side. A side with no Algorand coin shows the account with no balance.
     */
    private fun algorandAccounts(
        wallet: PairedWallet,
        mode: NetworkMode,
        coins: List<NativeCoin>,
        cache: List<CachedNativeBalance>,
    ): List<WalletAccount> {
        val network = if (mode == NetworkMode.TESTNET) Networks.ALGO_TESTNET else Networks.ALGO_MAINNET
        val coin = coins.firstOrNull { it.network.isAlgo }
        return wallet.algorand.mapIndexed { index, key ->
            val export = AccountExport(
                chain = network,
                signer = SignerRef(wallet.fingerprint.toInt(), AlgorandWallet.pathOf(index)),
                publicKey = PublicKey(Curve.ED25519_BIP32, key),
                chainCode = null,
                kind = AddressKind.DEFAULT,
            )
            WalletAccount(
                walletId = wallet.id,
                id = AlgorandWallet.accountFrom(export).single().id,
                index = index,
                networkName = coin?.name ?: familyLabel(Networks.ALGORAND),
                balance = nativeBalance(wallet.copy(solana = emptyList(), algorand = listOf(key)), coin, cache),
            )
        }
    }

    /** The native coin of [alone], the wallet that holds only this account's key, or null when the side has no [coin] for it. */
    private fun nativeBalance(alone: PairedWallet, coin: NativeCoin?, cache: List<CachedNativeBalance>): NativeAsset? =
        coin?.let { HomeBalances.nativeCoins(listOf(alone), cache, listOf(it)).singleOrNull() }
}
