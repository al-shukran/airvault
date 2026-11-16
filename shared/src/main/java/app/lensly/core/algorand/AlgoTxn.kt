package app.lensly.core.algorand

/**
 * An unsigned Algorand transaction that the app can review: a payment or an asset transfer. Get one only from
 * [AlgoTxnDecoder.decode].
 *
 * Addresses and hashes are the raw 32-byte values, not base32 text. The byte properties are arrays, and the generated
 * equals of the data classes compares arrays by reference, so compare them with `contentEquals`.
 */
sealed interface AlgoTxn {
    /** `snd`: the account the transaction is for. */
    val sender: ByteArray

    /** `fee`, in microAlgos. Zero when omitted, as it is for a group member whose fee another member pays. */
    val fee: ULong

    /** `fv`: the first round in which the transaction is valid. */
    val firstValid: ULong

    /** `lv`: the last round in which the transaction is valid. */
    val lastValid: ULong

    /** `gen`: the genesis ID of the network. Empty when omitted. */
    val genesisId: String

    /** `gh`: the genesis hash of the network. */
    val genesisHash: ByteArray

    /** `note`: up to 1024 bytes. Empty when omitted. */
    val note: ByteArray

    /** `lx`: the lease, or null when omitted. */
    val lease: ByteArray?

    /** `grp`: the group ID, or null when the transaction is not part of a group. */
    val group: ByteArray?

    /** `rekey`: the address that becomes the sender's auth address once this transaction applies, or null. */
    val rekeyTo: ByteArray?
}

/** A payment (`type` pay). */
data class Payment(
    override val sender: ByteArray,
    override val fee: ULong,
    override val firstValid: ULong,
    override val lastValid: ULong,
    override val genesisId: String,
    override val genesisHash: ByteArray,
    override val note: ByteArray,
    override val lease: ByteArray?,
    override val group: ByteArray?,
    override val rekeyTo: ByteArray?,
    /** `rcv`: the receiving account. */
    val receiver: ByteArray,
    /** `amt`, in microAlgos. Zero when omitted. */
    val amount: ULong,
    /** `close`: the account that takes the sender's remaining balance and closes the sender, or null. */
    val closeTo: ByteArray?,
) : AlgoTxn

/**
 * An asset transfer (`type` axfer). An opt-in is a transfer of amount 0 to the sender's own address. The encoding omits
 * the zero amount, so the decoded amount is 0.
 */
data class AssetTransfer(
    override val sender: ByteArray,
    override val fee: ULong,
    override val firstValid: ULong,
    override val lastValid: ULong,
    override val genesisId: String,
    override val genesisHash: ByteArray,
    override val note: ByteArray,
    override val lease: ByteArray?,
    override val group: ByteArray?,
    override val rekeyTo: ByteArray?,
    /** `xaid`: the asset ID. */
    val assetId: ULong,
    /** `aamt`, in asset units. Zero when omitted. */
    val amount: ULong,
    /** `arcv`: the receiving account. */
    val receiver: ByteArray,
    /** `aclose`: the account that takes the sender's remaining holding and closes it, or null. */
    val closeTo: ByteArray?,
    /** `asnd`: for a clawback, the account the assets are taken from. Null otherwise. */
    val assetSender: ByteArray?,
) : AlgoTxn

private val TX_TAG = byteArrayOf(0x54, 0x58)

/**
 * The bytes an Algorand account signs: "TX" (0x54 0x58) followed by the transaction bytes exactly as they were received.
 * Pass the bytes that [AlgoTxnDecoder.decode] accepted. Never re-encode the decoded fields, so the signature covers the
 * bytes the owner reviewed. The tag is go-algorand's `Transaction` hash ID
 * (https://github.com/algorand/go-algorand/blob/master/protocol/hash.go).
 */
fun bytesToSign(raw: ByteArray): ByteArray = TX_TAG + raw
