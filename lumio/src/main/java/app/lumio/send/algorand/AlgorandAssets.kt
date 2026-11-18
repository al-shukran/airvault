package app.lumio.send.algorand

import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.algorand.AlgorandFormat
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A coin an Algorand send can move: ALGO ([assetId] null), or an asset with its [symbol] and [decimals]. [held] is what the account holds of the asset, in
 * its base units. It is null when the account has not opted in to the asset, which only the Add asset opt-in plans. ALGO has no [held].
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

    /** ALGO, which the account always holds. */
    val native = AlgorandAsset(assetId = null, symbol = "ALGO", decimals = ALGO_DECIMALS, held = null)

    /**
     * Whether the account has opted in to [assetId]. A holding with a zero amount counts, since an opted-in asset holds nothing until it is sent to, so
     * [holdings] must list every opted-in asset, zero balances included.
     */
    fun optedIn(assetId: ULong, holdings: List<AsaHolding>): Boolean = holdings.any { it.assetId == assetId }

    /** The assets whose symbol and decimals the picker needs: each asset the account holds with an amount. */
    fun infoIds(holdings: List<AsaHolding>): List<ULong> = holdings.filter { it.amount.signum() > 0 }.map { it.assetId }

    /**
     * The coins the send offers: ALGO first, then each asset that the account holds with an amount above zero. [infos] holds the parameters of the assets
     * that [infoIds] lists. An asset whose creator left the unit name empty is named by its ID, as the account screen names it. An asset the account has
     * not opted in to is not offered: the Add asset screen opts in to it.
     */
    fun choices(holdings: List<AsaHolding>, infos: Map<ULong, AsaInfo>): List<AlgorandAsset> =
        listOf(native) + holdings.filter { it.amount.signum() > 0 }.map { asset(it.assetId, infos).copy(held = it.amount) }

    private fun asset(assetId: ULong, infos: Map<ULong, AsaInfo>): AlgorandAsset {
        val info = checkNotNull(infos[assetId]) { "No params for asset $assetId." }
        return AlgorandAsset(assetId, info.unitName.ifEmpty { "ASA $assetId" }, info.decimals, held = null)
    }

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
