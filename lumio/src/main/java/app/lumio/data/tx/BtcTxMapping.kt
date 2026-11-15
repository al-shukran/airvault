package app.lumio.data.tx

import app.lumio.btc.EsploraTx
import app.lumio.data.net.NetworkId
import java.math.BigInteger

/** Bitcoin's native coin, whose base unit is the satoshi: 8 decimals. */
val BTC_ASSET = TxAsset(contract = null, symbol = "BTC", name = "Bitcoin", decimals = 8)

/**
 * One Bitcoin transaction as the account [account] sees it, read from the transaction that an Esplora server lists for the address. [tipHeight] is the
 * chain tip, which gives the confirmations. [observedAtMs] is the time to use for a transaction that is not in a block yet.
 *
 * The direction follows the account's inputs and outputs:
 * - when no input spends from the account, the transaction is IN, and the amount is what the account's outputs receive;
 * - when every input and every output belongs to the account, it is SELF, and the fee is the inputs less the outputs;
 * - otherwise it is OUT, and the amount is the value of the outputs that go to other addresses. The fee is known only when every input is the account's.
 */
fun EsploraTx.toLumioTx(account: String, net: NetworkId, tipHeight: Long, observedAtMs: Long): LumioTx {
    val fromAccount = inputs.sumOf { input -> input.prevout?.takeIf { it.address == account }?.valueSats ?: 0L }
    val toAccount = outputs.sumOf { output -> output.takeIf { it.address == account }?.valueSats ?: 0L }
    val toOthers = outputs.filter { it.address != account }
    val allInputsMine = inputs.isNotEmpty() && inputs.all { it.prevout?.address == account }
    val totalIn = inputs.sumOf { input -> input.prevout?.valueSats ?: 0L }
    val totalOut = outputs.sumOf { it.valueSats }
    val direction = when {
        fromAccount == 0L -> TxDirection.IN
        allInputsMine && toOthers.all { it.valueSats == 0L } -> TxDirection.SELF
        else -> TxDirection.OUT
    }
    // An answer whose outputs exceed its inputs is inconsistent: its fee is unknown, not negative.
    val feeSats = (totalIn - totalOut).takeIf { it >= 0L }
    val fee = when {
        direction == TxDirection.IN -> null
        direction == TxDirection.SELF || allInputsMine -> feeSats?.let { BigInteger.valueOf(it) }
        else -> null
    }
    val counterparty = when (direction) {
        TxDirection.IN -> inputs.firstNotNullOfOrNull { it.prevout?.address }
        TxDirection.OUT -> toOthers.firstNotNullOfOrNull { it.address }
        TxDirection.SELF -> null
    }
    val amount = when (direction) {
        TxDirection.IN, TxDirection.SELF -> toAccount
        TxDirection.OUT -> toOthers.sumOf { it.valueSats }
    }
    val confirmed = blockHeight != null
    return LumioTx(
        id = "${net.family.name}:${net.chain}:$txid",
        network = net,
        account = account,
        hash = txid,
        timestampMs = blockTimeSec?.let { it * MILLIS } ?: observedAtMs,
        asset = BTC_ASSET,
        amount = BigInteger.valueOf(amount),
        direction = direction,
        fee = fee,
        counterparty = counterparty,
        status = if (confirmed) TxStatus.CONFIRMED else TxStatus.PENDING,
        confirmations = blockHeight?.let { maxOf(0L, tipHeight - it + 1) } ?: 0L,
        inputData = null,
        memo = null,
    )
}

private const val MILLIS = 1_000L
