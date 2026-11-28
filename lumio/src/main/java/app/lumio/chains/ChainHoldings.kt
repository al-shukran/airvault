package app.lumio.chains

import android.content.Context
import app.airvault.chains.solana.signer.SolanaTokenList
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AsaAssetInfo
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaHoldings
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.SplTokenBalances
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.ui.kit.addressLineText
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * One token that an account holds on a Solana or Algorand network, as the account screen lists it: its [symbol], its [amount] in base units, the
 * [decimals] that turn the amount into whole units, the [network] it sits on, and its [asset], whose price it has. Its [key] is the mint or the asset ID
 * that names it in the asset route.
 */
data class ChainHolding(val symbol: String, val network: String, val amount: BigInteger, val decimals: Int, val asset: AssetId.Token) {
    val key: String get() = asset.tokenId
}

/**
 * The SPL tokens and Algorand assets that an address holds, read from the network now. A token's decimals come from the network: the SPL account's
 * `tokenAmount`, and the asset's params. Only holdings with an amount above zero are listed.
 */
object ChainHoldings {
    private const val SOLANA_TOKENS = "registry/solana-tokens.json"

    @Volatile
    private var tokenNames: SolanaTokenList? = null

    /** The SPL tokens the app names, read once from the bundled list. */
    fun solanaNames(context: Context): SolanaTokenList = tokenNames ?: context.assets.open(SOLANA_TOKENS).bufferedReader().use { reader ->
        SolanaTokenList.parse(reader.readText())
    }.also { tokenNames = it }

    /** The SPL tokens that [owner] holds on [network], under the classic program and Token-2022. */
    suspend fun solana(scheduler: Scheduler, network: NetworkId, owner: String, names: SolanaTokenList, networkName: String): List<ChainHolding> =
        solanaRows(scheduler.run(SplTokenBalances(network, owner), Priority.FOREGROUND), names, network, networkName)

    /** The rows of [balances] that hold an amount. A token the list names takes the list's symbol, and any other is named by its mint in groups of four, cut to its two first and two last. */
    fun solanaRows(balances: List<SplBalance>, names: SolanaTokenList, network: NetworkId, networkName: String): List<ChainHolding> =
        balances.filter { it.amount.signum() > 0 }.map { balance ->
            ChainHolding(
                symbol = names.token(balance.mint)?.symbol ?: mintName(balance.mint),
                network = networkName,
                amount = balance.amount,
                decimals = balance.decimals,
                asset = AssetId.Spl(network, balance.mint),
            )
        }

    /** The assets that [address] holds on [network] with an amount above zero, each with the params that give its decimals and unit name. */
    suspend fun algorand(scheduler: Scheduler, network: NetworkId, address: String, networkName: String): List<ChainHolding> {
        val held = scheduler.run(AsaHoldings(network, address), Priority.FOREGROUND).filter { it.amount.signum() > 0 }
        val params = held.associate { it.assetId to info(scheduler, network, it.assetId) }
        return algorandRows(held, params, network, networkName)
    }

    /** The rows of [held], each with the params of its asset in [params]. An asset whose creator left the unit name empty is named by its ID. */
    fun algorandRows(held: List<AsaHolding>, params: Map<ULong, AsaInfo>, network: NetworkId, networkName: String): List<ChainHolding> = held.map { asset ->
        val info = checkNotNull(params[asset.assetId]) { "No params for asset ${asset.assetId}." }
        ChainHolding(
            symbol = info.unitName.ifEmpty { "ASA ${asset.assetId}" },
            network = networkName,
            amount = asset.amount,
            decimals = info.decimals,
            asset = AssetId.Asa(network, asset.assetId),
        )
    }

    private val paramsCache = ConcurrentHashMap<String, AsaInfo>()

    /** The params of [assetId] on [network]. A read is kept for the life of the process, since an asset's decimals and unit name do not change. */
    suspend fun info(scheduler: Scheduler, network: NetworkId, assetId: ULong): AsaInfo {
        val key = "${network.chain}/$assetId"
        paramsCache[key]?.let { return it }
        return scheduler.run(AsaAssetInfo(network, assetId), Priority.FOREGROUND).also { paramsCache[key] = it }
    }

    /** The name of a token the list does not know: its [mint] as an address is written, in groups of four with the ends and an ellipsis. */
    internal fun mintName(mint: String): String = addressLineText(mint)
}
