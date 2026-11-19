package app.lumio.home

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.BtcRow
import app.lumio.btc.networkId
import app.lumio.chains.CachedNativeBalance
import app.lumio.chains.NativeBalances
import app.lumio.chains.NativeCoin
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.evm.EvmChain
import app.lumio.evm.Holding
import app.lumio.pair.PairedWallet
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The native balances that Home lists, built from the reads the vault holds. They are not read from the network here, so a read that the vault does
 * not hold is not an asset.
 */
object HomeBalances {
    /**
     * The native coin of each chain in [chains] that has a read in [holdings], summed over the wallets and their addresses. A chain with no read has no
     * asset, since its balance is not known yet, so it is never shown as zero.
     */
    fun evm(chains: List<EvmChain>, holdings: List<Holding>): List<NativeAsset> = chains.mapNotNull { chain ->
        val reads = holdings.filter { it.chain.chainId == chain.chainId }
        if (reads.isEmpty()) return@mapNotNull null
        val wei = reads.fold(BigInteger.ZERO) { sum, holding -> sum + holding.wei }
        val network = NetworkId(Networks.evm(chain.chainId), chain.testnet)
        NativeAsset(
            id = NativeAsset.idOf(network),
            symbol = chain.symbol,
            name = chain.name,
            network = network,
            balance = BigDecimal(wei).movePointLeft(chain.decimals),
        )
    }

    /**
     * The Bitcoin coin of each network that has a scan in [rows]. Its balance is the confirmed balance of the scanned accounts, the figure the Bitcoin
     * pages show first. The pending amounts stay on those pages.
     */
    fun bitcoin(rows: List<BtcRow>): List<NativeAsset> = rows
        .filter { it.snapshot != null }
        .groupBy { it.ref.account.network }
        .entries
        .sortedBy { it.key.ordinal }
        .map { (network, scanned) ->
            val sats = scanned.sumOf { row -> row.snapshot?.confirmedSats ?: 0L }
            val id = network.networkId()
            NativeAsset(
                id = NativeAsset.idOf(id),
                symbol = "BTC",
                name = if (network == BtcNetwork.TESTNET4) "Bitcoin Testnet4" else "Bitcoin",
                network = id,
                balance = BigDecimal.valueOf(sats).movePointLeft(BTC_DECIMALS),
            )
        }

    /**
     * The Algorand and Solana coin of each of [coins] that has a read in [cache] for an address of [wallets], summed over the wallets and their addresses. A
     * coin with no read is not an asset, so it is never shown as zero.
     */
    fun nativeCoins(wallets: List<PairedWallet>, cache: List<CachedNativeBalance>, coins: List<NativeCoin>): List<NativeAsset> {
        val byKey = cache.associateBy { it.key }
        return coins.mapNotNull { coin ->
            val reads = wallets.flatMap { wallet ->
                NativeBalances.addresses(wallet, coin.network).mapNotNull { address -> byKey[Triple(wallet.id, coin.network, address)] }
            }
            if (reads.isEmpty()) {
                null
            } else {
                val base = reads.fold(BigInteger.ZERO) { sum, read -> sum + BigInteger(read.amount) }
                NativeAsset(
                    id = NativeAsset.idOf(coin.network),
                    symbol = coin.symbol,
                    name = coin.name,
                    network = coin.network,
                    balance = BigDecimal(base).movePointLeft(coin.decimals),
                )
            }
        }
    }

    private const val BTC_DECIMALS = 8
}
