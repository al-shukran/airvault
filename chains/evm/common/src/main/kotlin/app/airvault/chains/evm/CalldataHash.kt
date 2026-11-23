package app.airvault.chains.evm

/**
 * The label of the row that shows the Keccak-256 of a call's calldata. Lensly shows it for a call it cannot read, and Lumio shows it on the review of
 * a send, so the owner can compare the two. It is not the Data Hash of a record, which is the SHA-256 of the whole payload.
 */
const val CALLDATA_HASH_LABEL = "Calldata Hash (Keccak-256)"

/** The Keccak-256 of [calldata] as 0x and 64 lowercase hex digits. Lensly and Lumio both show this string for the same calldata. */
fun calldataHash(calldata: ByteArray): String = "0x" + keccak256(calldata).toHex()
