package app.lumio.btc

import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFeeRate
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcPsbt
import app.lensly.core.btc.BtcUnsignedPsbt
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.CacheUse
import app.lumio.data.cache.DEFAULT_SHORT_TTL_MS
import app.lumio.data.cache.jsonCodec
import app.lumio.data.provider.AddressStats
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.RawTx
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.Either
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.math.ceil

/** The smallest output Lumio builds. A node will not relay a smaller one, so a remainder below it goes to the fee instead of a change output. */
internal const val DUST_SATS = 546L

/** The branch of a receive address, and of a change address, in a request's derivation. */
private const val RECEIVE_BRANCH = 0
private const val CHANGE_BRANCH = 1

/** The confirmation target whose estimate the form starts from, in blocks. */
private const val SUGGESTED_TARGET_BLOCKS = 3

/** The lowest fee rate a request pays, in sat/vB, and the highest that the form accepts. */
private const val MIN_FEE_RATE = 1.0
private const val MAX_FEE_RATE = 1000.0

/** The unspent outputs of one account that a send may spend, and the change index a send pays its change to: the first unused change address. */
class BtcSpendable(val coins: List<BtcCoin>, val nextChangeIndex: Int)

/**
 * A send from one account, as far as Lumio can build it without a key: the coins it spends, the fee and the change, and the unsigned PSBT
 * that Lensly signs. [changeIndex] is null when the remainder is too small to pay out and goes to the fee.
 */
class BtcSendPlan(
    val network: BtcNetwork,
    val account: BtcWatchOnlyAccount,
    val recipient: String,
    val amountSats: Long,
    val feeRateSatPerVb: Double,
    val coins: List<BtcCoin>,
    val changeIndex: Int?,
    val changeSats: Long,
    val feeSats: Long,
    val vsize: Long,
    val psbt: Psbt,
) {
    /** The unsigned transaction. A signed request must carry it unchanged. */
    val tx: Transaction get() = psbt.global.tx

    /** The total of the coins the send spends, in satoshis. */
    val inputSats: Long get() = coins.sumOf { it.valueSats }

    /** The sign request as the frames of a `ur:crypto-psbt`: one QR code, or the frames of an animated one. */
    fun parts(): List<String> = BtcPsbt.encodeUrParts(psbt)
}

/** The coins a send spends, the fee they pay, and the change that comes back (0 when there is none). */
internal class CoinPick(val coins: List<BtcCoin>, val feeSats: Long, val changeSats: Long)

/**
 * A send of one account, built from public data only. Lumio reads the account's coins, picks the ones to spend, builds the unsigned PSBT,
 * checks Lensly's signature against it, and broadcasts the result.
 */
object BtcSend {
    /**
     * The confirmed unspent outputs of [account], each with the derivation of its key, and the first change index that has never been used.
     * It reads the same chains as the scan, with the gap limit, and never a balance that the scan would not reach. Unconfirmed outputs are
     * left out, so a send never spends an output that a node may not yet relay.
     */
    suspend fun spendable(account: BtcWatchOnlyAccount, scheduler: Scheduler): BtcSpendable {
        val net = account.network.networkId()
        val memo: ConcurrentMap<String, EsploraAddressStats> = ConcurrentHashMap()
        val statsOf: suspend (String) -> EsploraAddressStats = { address ->
            scheduler.run(
                AddressStats(net, address),
                Priority.FOREGROUND,
                CacheUse(CachePolicy.ShortTtl(DEFAULT_SHORT_TTL_MS), "stats/${net.chain}/$address", jsonCodec()),
            )
        }
        val receive = scanChain(account::receiveAddress, statsOf, memo)
        val change = scanChain(account::changeAddress, statsOf, memo)
        val funded = receive.used.map { RECEIVE_BRANCH to it } + change.used.map { CHANGE_BRANCH to it }
        val listed = funded.filter { it.second.stats.balance != 0L }.flatMap { (branch, hit) ->
            scheduler.run(AddressUtxos(net, hit.address), Priority.FOREGROUND)
                .filter { it.blockHeight != null }
                .map { Triple(branch, hit.index, it) }
        }
        val previous = listed.map { it.third.txid }.distinct().associateWith { txid ->
            val tx = Transaction.read(scheduler.run(RawTx(net, txid), Priority.FOREGROUND))
            require(tx.txid.toString() == txid) { "The server's transaction does not match the id it was asked for." }
            tx
        }
        val coins = listed.map { (branch, index, utxo) -> BtcCoin(previous.getValue(utxo.txid), utxo.vout, branch, index) }
        val usedChange = change.used.map { it.index }.toSet()
        return BtcSpendable(coins, nextChangeIndex = generateSequence(0) { it + 1 }.first { it !in usedChange })
    }

    /**
     * The send of [amountSats] to [recipient] at [feeRateSatPerVb]. The recipient must be an address of the account's network. Throws
     * IllegalArgumentException, with the owner's words, when the coins do not cover the amount and the fee.
     */
    fun plan(
        account: BtcWatchOnlyAccount,
        spendable: BtcSpendable,
        recipient: String,
        amountSats: Long,
        feeRateSatPerVb: Double,
    ): BtcSendPlan {
        require(amountSats >= DUST_SATS) { "The amount is below the dust limit of $DUST_SATS sat." }
        val network = account.network
        val recipientScript = scriptOf(network, recipient)
        val changeScript = scriptOf(network, account.changeAddress(spendable.nextChangeIndex))
        fun outputs(change: Long?): List<TxOut> = listOfNotNull(
            TxOut(Satoshi(amountSats), recipientScript),
            change?.let { TxOut(Satoshi(it), changeScript) },
        )
        val pick = chooseCoins(spendable.coins, amountSats, feeRateSatPerVb) { coins, withChange ->
            BtcFeeRate.estimatedVsize(BtcUnsignedPsbt.transaction(coins, outputs(if (withChange) 0L else null)), account.scriptType)
        }
        val withChange = pick.changeSats > 0
        val psbt = BtcUnsignedPsbt.build(account, pick.coins, outputs(if (withChange) pick.changeSats else null))
        return BtcSendPlan(
            network = network,
            account = account,
            recipient = recipient,
            amountSats = amountSats,
            feeRateSatPerVb = feeRateSatPerVb,
            coins = pick.coins,
            changeIndex = if (withChange) spendable.nextChangeIndex else null,
            changeSats = pick.changeSats,
            feeSats = pick.feeSats,
            vsize = BtcFeeRate.estimatedVsize(psbt.global.tx, account.scriptType),
            psbt = psbt,
        )
    }

    /**
     * The signed transaction that [ur] carries for [plan]. The answer must be a `ur:crypto-psbt` whose unsigned transaction is the one
     * [plan] made, so the inputs and outputs are the ones the owner was shown, and every input must carry Lensly's signature. Throws
     * IllegalArgumentException, with the owner's words, for any other answer.
     */
    fun signed(plan: BtcSendPlan, ur: UR): Transaction {
        val psbt = try {
            BtcPsbt.fromUr(ur)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("This code is not a signed request from Lensly.", e)
        }
        require(psbt.global.tx.txid == plan.tx.txid) { "This signature is for a different transaction." }
        val tx = BtcPsbt.finalizeAndExtract(psbt)
        require(tx.txOut == plan.tx.txOut && tx.txIn.map { it.outPoint } == plan.tx.txIn.map { it.outPoint }) {
            "The signed transaction does not pay the outputs that you made."
        }
        return tx
    }

    /**
     * Broadcasts [tx] on [network] and returns its id. The server must report the id of [tx], so the owner is never sent to a transaction that
     * Lumio did not sign. The broadcast is routed by the scheduler, as every Bitcoin call is.
     */
    suspend fun broadcast(scheduler: Scheduler, network: BtcNetwork, tx: Transaction): String {
        val expected = tx.txid.toString()
        val reported = scheduler.run(Broadcast(network.networkId(), Transaction.write(tx)), Priority.FOREGROUND)
        require(reported == expected) { "The server reported a different transaction id. Check the explorer before you send again." }
        return reported
    }

    /** The fee rate the form starts at: the estimate for about three blocks, or the cheapest the server gives, rounded up and never under 1. */
    fun suggestedFeeRate(estimates: Map<Int, Double>): Double {
        val rate = estimates[SUGGESTED_TARGET_BLOCKS] ?: estimates.values.minOrNull() ?: MIN_FEE_RATE
        return ceil(rate.coerceAtLeast(MIN_FEE_RATE))
    }
}

/**
 * Largest first. Coins are added in order of value until they cover the amount and the fee, which is the fee rate times the estimated vsize of
 * the request, rounded up. Change is paid out only when it is at least [DUST_SATS]. A smaller remainder goes to the fee.
 * [vsizeOf] gives the vsize of a request that spends the coins it is given, with or without its change output.
 */
internal fun chooseCoins(
    coins: List<BtcCoin>,
    amountSats: Long,
    feeRateSatPerVb: Double,
    vsizeOf: (List<BtcCoin>, Boolean) -> Long,
): CoinPick {
    require(coins.isNotEmpty()) { "This account has no confirmed balance to send." }
    val chosen = mutableListOf<BtcCoin>()
    var total = 0L
    for (coin in coins.sortedByDescending { it.valueSats }) {
        chosen += coin
        total += coin.valueSats
        val feeWithChange = feeFor(vsizeOf(chosen, true), feeRateSatPerVb)
        val change = total - amountSats - feeWithChange
        if (change >= DUST_SATS) return CoinPick(chosen.toList(), feeWithChange, change)
        val feeWithout = feeFor(vsizeOf(chosen, false), feeRateSatPerVb)
        if (total - amountSats >= feeWithout) return CoinPick(chosen.toList(), total - amountSats, 0L)
    }
    throw IllegalArgumentException("The confirmed balance does not cover this amount and its fee.")
}

/** The fee of a request of [vsize] vbytes at [feeRateSatPerVb], rounded up to a whole satoshi. */
internal fun feeFor(vsize: Long, feeRateSatPerVb: Double): Long = ceil(vsize * feeRateSatPerVb).toLong()

/** The output script of [address] on [network]. Throws IllegalArgumentException when [address] is not one of that network. */
internal fun scriptOf(network: BtcNetwork, address: String): ByteArray =
    when (val script = Bitcoin.addressToPublicKeyScript(network.chain.chainHash, address)) {
        is Either.Left -> throw IllegalArgumentException("This is not a valid ${if (network == BtcNetwork.MAINNET) "mainnet" else "testnet4"} address.")
        is Either.Right -> Script.write(script.value)
    }

/** The owner's inputs for a send, checked. Each check throws IllegalArgumentException with the words the form shows. */
object BtcSendInput {
    private const val MAX_AMOUNT_CHARS = 20
    private const val SAT_DECIMALS = 8
    private val MAX_BTC = BigDecimal("21000000")
    private val DECIMAL = Regex("\\d+(\\.\\d+)?")

    /** The recipient, trimmed, when it is an address of [network]. */
    fun recipient(text: String, network: BtcNetwork): String {
        val address = text.trim()
        require(address.isNotEmpty()) { "Enter the recipient address." }
        try {
            scriptOf(network, address)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Enter a ${if (network == BtcNetwork.MAINNET) "mainnet" else "testnet4"} address.", e)
        }
        return address
    }

    /** The amount of [text] in BTC, in satoshis. It has at most 8 decimal places, is more than the dust limit, and is at most all bitcoin. */
    fun amountSats(text: String): Long {
        val amount = text.trim()
        require(amount.length <= MAX_AMOUNT_CHARS && DECIMAL.matches(amount)) { "Enter an amount in BTC, such as 0.001." }
        val value = BigDecimal(amount)
        require(value <= MAX_BTC) { "The amount is more than all the bitcoin there is." }
        require(value.stripTrailingZeros().scale() <= SAT_DECIMALS) { "Use at most 8 decimal places for BTC." }
        val sats = value.movePointRight(SAT_DECIMALS).toBigIntegerExact().toLong()
        require(sats >= DUST_SATS) { "The amount must be at least $DUST_SATS sat, the dust limit." }
        return sats
    }

    /** The fee rate of [text] in sat/vB, from 1 to 1000. */
    fun feeRate(text: String): Double {
        val rate = text.trim()
        require(rate.length <= 8 && DECIMAL.matches(rate)) { "Enter a fee rate in sat/vB, such as 2." }
        val value = rate.toDouble()
        require(value >= MIN_FEE_RATE && value <= MAX_FEE_RATE) { "The fee rate must be from 1 to 1000 sat/vB." }
        return value
    }
}
