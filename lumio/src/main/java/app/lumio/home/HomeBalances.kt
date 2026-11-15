package app.lumio.home

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.BtcRow
import app.lumio.btc.networkId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.evm.EvmChain
import app.lumio.evm.Holding
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
        val network = NetworkId(Family.EVM, chain.chainId.toString(), chain.testnet)
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
                name = if (network == BtcNetwork.TESTNET4) "Bitcoin testnet4" else "Bitcoin",
                network = id,
                balance = BigDecimal.valueOf(sats).movePointLeft(BTC_DECIMALS),
            )
        }

    private const val BTC_DECIMALS = 8
}
