package app.lumio.data.tx

import app.lumio.data.net.NetworkId
import java.math.BigInteger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Which way a transaction moved value for the account: in, out, or from the account to itself. */
@Serializable
enum class TxDirection { IN, OUT, SELF }

/** [PENDING] is not in a block yet. [FAILED] is a confirmed transaction that reverted, so it moved no value. */
@Serializable
enum class TxStatus { PENDING, CONFIRMED, FAILED }

/**
 * The asset that a transaction moved. [contract] is null for the network's native coin. For an ERC-20 token it is the contract address in lower case.
 * [decimals] scales [LumioTx.amount] to whole units.
 */
@Serializable
data class TxAsset(val contract: String?, val symbol: String, val name: String, val decimals: Int) {
    init {
        require(decimals in 0..MAX_DECIMALS) { "Decimals are 0 to $MAX_DECIMALS." }
    }

    val isNative: Boolean get() = contract == null

    private companion object {
        /** A 256-bit amount has at most 78 digits, so 77 decimals is the widest scale that holds a whole unit. */
        const val MAX_DECIMALS = 77
    }
}

/**
 * One transaction as it appears in one account's history. [id] names the transaction for comments and for the store: the network, the hash, and for an
 * ERC-20 transfer the transfer's contract, parties, amount and ordinal, since the explorers name no log index. [account] is the address the history
 * was read for, and [hash] is the transaction's hash as its network reports it.
 *
 * [amount] is in base units of [asset] and is never negative. [fee] is in base units of the native coin, and it is set only when the account paid the
 * fee. [counterparty] is the other address, or null for a self transfer. [inputData] is the EVM input data as hex, and [memo] is that data as text
 * when it is printable text. [confirmations] is the depth at which the network reported the transaction, and [blockNumber] is the height of the block that
 * holds it, null while it is not in a block yet. [spam] is set by the spam heuristic, not by a provider.
 */
@Serializable
data class LumioTx(
    val id: String,
    val network: NetworkId,
    val account: String,
    val hash: String,
    val timestampMs: Long,
    val asset: TxAsset,
    @Serializable(with = BigIntegerSerializer::class) val amount: BigInteger,
    val direction: TxDirection,
    @Serializable(with = BigIntegerSerializer::class) val fee: BigInteger?,
    val counterparty: String?,
    val status: TxStatus,
    val confirmations: Long,
    val blockNumber: Long? = null,
    val inputData: String?,
    val memo: String?,
    val spam: Boolean = false,
) {
    init {
        require(amount.signum() >= 0) { "An amount is not negative." }
        require(fee == null || fee.signum() >= 0) { "A fee is not negative." }
    }
}

/** A [BigInteger] as its decimal text, so that no float can round it. */
object BigIntegerSerializer : KSerializer<BigInteger> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.lumio.data.tx.BigInteger", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigInteger) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): BigInteger = BigInteger(decoder.decodeString())
}
