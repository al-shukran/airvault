package app.lumio.data.tx

import app.lumio.data.net.NetworkId
import app.lumio.data.provider.explorer.ExplorerTx
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** The longest input text that is shown as a memo. A longer one is left out, since it is data and not a message. */
private const val MAX_MEMO_CHARS = 1_000

/**
 * The rows of one EVM account's history as [LumioTx]. [account] is the address that the request named, [net] its network, and [native] the network's
 * native coin, as the registry names it. The direction follows the account: a row that the account sent is OUT, one that it received is IN, and one that
 * it sent to itself is SELF. The fee is the gas of a transaction the account sent. It is set on the normal transaction only, so a token transfer of that
 * transaction does not count the fee a second time. Rows must already name the account, as [involving][app.lumio.data.provider.explorer.involving] checks.
 */
fun List<ExplorerTx>.toLumioTxs(account: String, net: NetworkId, native: TxAsset): List<LumioTx> {
    val me = account.lowercase()
    return map { row ->
        val direction = when {
            row.from == me && row.to == me -> TxDirection.SELF
            row.from == me -> TxDirection.OUT
            else -> TxDirection.IN
        }
        when (row) {
            is ExplorerTx.Normal -> LumioTx(
                id = txIdOf(net, row.hash),
                network = net,
                account = account,
                hash = row.hash,
                timestampMs = row.timestampSec * MILLIS,
                asset = native,
                amount = row.valueWei,
                direction = direction,
                fee = if (row.from == me) row.gasUsed * row.gasPrice else null,
                counterparty = counterpartyOf(direction, row.from, row.to ?: row.createdContract),
                status = if (row.failed) TxStatus.FAILED else TxStatus.CONFIRMED,
                confirmations = row.confirmations,
                inputData = row.input.takeIf { it != EMPTY_INPUT },
                memo = memoOf(row.input),
            )
            is ExplorerTx.Token -> LumioTx(
                id = txIdOf(net, row.hash, row),
                network = net,
                account = account,
                hash = row.hash,
                timestampMs = row.timestampSec * MILLIS,
                asset = TxAsset(contract = row.contract, symbol = row.symbol, name = row.name, decimals = row.decimals),
                amount = row.amount,
                direction = direction,
                fee = null,
                counterparty = counterpartyOf(direction, row.from, row.to),
                status = TxStatus.CONFIRMED,
                confirmations = row.confirmations,
                inputData = null,
                memo = null,
            )
        }
    }
}

/**
 * The id of an EVM transaction: `EVM:{chain}:{hash}`. An ERC-20 transfer adds its contract, parties, amount and ordinal, because the explorers name
 * no log index and one transaction may carry several transfers.
 */
internal fun txIdOf(net: NetworkId, hash: String, transfer: ExplorerTx.Token? = null): String {
    val base = "${net.family.name}:${net.chain}:$hash"
    return if (transfer == null) {
        base
    } else {
        "$base:${transfer.contract}:${transfer.from}:${transfer.to.orEmpty()}:${transfer.amount}:${transfer.ordinal}"
    }
}

private fun counterpartyOf(direction: TxDirection, from: String, to: String?): String? = when (direction) {
    TxDirection.IN -> from
    TxDirection.OUT -> to
    TxDirection.SELF -> null
}

/**
 * The input data as a memo: UTF-8 text with no control characters, other than a line break or a tab. Input that is a contract call, that is not text,
 * or that is longer than [MAX_MEMO_CHARS] gives no memo. `0x` gives none.
 */
internal fun memoOf(input: String): String? {
    if (input.length <= EMPTY_INPUT.length) return null
    val bytes = input.substring(2).chunked(2).map { it.toInt(radix = 16).toByte() }.toByteArray()
    val text = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        return null
    }
    val printable = text.none { c -> Character.isISOControl(c) && c != '\n' && c != '\t' } && '�' !in text
    return text.takeIf { printable && it.isNotBlank() && it.length <= MAX_MEMO_CHARS }
}

private const val EMPTY_INPUT = "0x"
private const val MILLIS = 1_000L
