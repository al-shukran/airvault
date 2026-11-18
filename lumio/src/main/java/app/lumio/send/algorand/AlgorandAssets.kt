package app.lumio.send.algorand

import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.algorand.AlgorandFormat
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A coin an Algorand send can move: ALGO ([assetId] null), or an asset with its [symbol] and [decimals]. [held] is what the account holds of the asset, in
 * its base units. It is null when the account has not opted in to the asset, and then the send's primary action is the opt-in. ALGO has no [held].
 */
data class AlgorandAsset(val assetId: ULong?, val symbol: String, val decimals: Int, val held: BigInteger?) {
    /** The coin's key in the picker: the asset's ID, or [AlgorandAssets.ALGO_KEY] for ALGO. */
    val key: String get() = assetId?.toString() ?: AlgorandAssets.ALGO_KEY
}

/** The coins an Algorand send offers, the opt-in decision, the plan of a send of each coin, and the amounts in base units. */
object AlgorandAssets {
    /** The picker's key for ALGO. */
    const val ALGO_KEY = "algo"

    /** ALGO's decimal places: a microAlgo is a millionth of an ALGO. */
    private const val ALGO_DECIMALS = 6

    /** USDC on testnet and on mainnet: the assets that the send offers to opt in to. */
    private val TESTNET_USDC = 10458941UL
    private val MAINNET_USDC = 31566704UL

    /** ALGO, which the account always holds. */
    val native = AlgorandAsset(assetId = null, symbol = "ALGO", decimals = ALGO_DECIMALS, held = null)

    /** The assets that the send offers to opt in to on [network]. */
    fun knownAssets(network: NetworkId): List<ULong> = listOf(if (network.testnet) TESTNET_USDC else MAINNET_USDC)

    /**
     * Whether the account has opted in to [assetId]. A holding with a zero amount counts, since an opted-in asset holds nothing until it is sent to, so
     * [holdings] must list every opted-in asset, zero balances included.
     */
    fun optedIn(assetId: ULong, holdings: List<AsaHolding>): Boolean = holdings.any { it.assetId == assetId }

    /** The assets whose symbol and decimals the picker needs: each asset held with an amount, and each known asset the account has not opted in to. */
    fun infoIds(holdings: List<AsaHolding>, known: List<ULong>): List<ULong> =
        holdings.filter { it.amount.signum() > 0 }.map { it.assetId } + known.filterNot { optedIn(it, holdings) }

    /**
     * The coins the send offers: ALGO first, then each asset that the account holds with an amount above zero, then each known asset that the account has
     * not opted in to, which the send opts in to. [infos] holds the parameters of the assets that [infoIds] lists. An asset whose creator left the unit
     * name empty is named by its ID, as the account screen names it.
     */
    fun choices(holdings: List<AsaHolding>, known: List<ULong>, infos: Map<ULong, AsaInfo>): List<AlgorandAsset> {
        val held = holdings.filter { it.amount.signum() > 0 }.map { asset(it.assetId, infos).copy(held = it.amount) }
        val notOptedIn = known.filterNot { optedIn(it, holdings) }.map { asset(it, infos) }
        return listOf(native) + held + notOptedIn
    }

    private fun asset(assetId: ULong, infos: Map<ULong, AsaInfo>): AlgorandAsset {
        val info = checkNotNull(infos[assetId]) { "No params for asset $assetId." }
        return AlgorandAsset(assetId, info.unitName.ifEmpty { "ASA $assetId" }, info.decimals, held = null)
    }

    /** The opt-in decision: an asset must be opted in to before the account can hold it. ALGO never needs one. */
    fun needsOptIn(asset: AlgorandAsset): Boolean = asset.assetId != null && asset.held == null

    /**
     * The plan of a send of [amount] of [asset] from the account of [signer] to [to] on [network], at [funds]: an ALGO payment, a transfer of an asset the
     * account holds, or the opt-in of an asset it has not opted in to, which takes neither [to] nor [amount]. [recipientOptedIn] says whether [to] holds
     * the asset. Throws IllegalArgumentException with the owner's words, as the planners do.
     */
    fun plan(
        asset: AlgorandAsset,
        network: NetworkId,
        funds: AlgorandFunds,
        fingerprint: Long,
        accountIndex: Int,
        signer: ByteArray,
        to: ByteArray,
        amount: BigInteger,
        recipientOptedIn: Boolean,
    ): AlgorandSendPlan {
        val assetId = asset.assetId ?: return AlgorandSend.plan(network, funds, fingerprint, accountIndex, signer, to, amount)
        val held = asset.held ?: return AsaSend.optIn(network, funds, fingerprint, accountIndex, signer, assetId)
        return AsaSend.plan(network, funds, fingerprint, accountIndex, signer, to, assetId, amount, held, recipientOptedIn)
    }

    /**
     * The base units that [text] names in a coin with [decimals] places: a plain amount with at most that many decimal places, above zero. [symbol] names
     * the coin in the words of a refusal.
     */
    fun units(text: String, decimals: Int, symbol: String): BigInteger {
        val trimmed = text.trim()
        val places = if (decimals == 0) "" else "(\\.[0-9]{1,$decimals})?"
        require(Regex("[0-9]{1,18}$places").matches(trimmed)) { "Enter an amount in $symbol, with up to $decimals decimal places." }
        val units = BigDecimal(trimmed).movePointRight(decimals).toBigIntegerExact()
        require(units.signum() > 0) { "Enter an amount above zero." }
        require(units.bitLength() < Long.SIZE_BITS) { "Enter a smaller amount." }
        return units
    }

    /** The amount in [units] of a coin with [decimals] places, as the form shows it: no trailing zeros. */
    fun text(units: BigInteger, decimals: Int): String = BigDecimal(units, decimals).stripTrailingZeros().toPlainString()

    /** The amount of [plan] as the review and the summary show it: ALGO as the account screen does, else in the coin's [symbol] and [decimals]. */
    fun amountText(plan: AlgorandSendPlan, symbol: String, decimals: Int): String =
        if (plan is AlgorandPaymentPlan) AlgorandFormat.amount(plan.amount) else "${text(plan.amount, decimals)} $symbol"
}
