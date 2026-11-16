package app.lensly.core.algorand

/**
 * Algorand accounts as the Ledger Algorand app derives them: account N is the Ed25519 key of `m/44'/283'/N'/0/0` ([LedgerEd25519]), and
 * its address is [AlgoAddress.encode] of the 32-byte public key.
 *
 * Each call derives the private key from [seed] and wipes it before it returns. Nothing here keeps a private key.
 */
object AlgorandAccount {

    /** The 32-byte Ed25519 public key of account [index]. [seed] is the 64-byte BIP39 seed, and the caller keeps it. */
    fun publicKey(seed: ByteArray, index: Int): ByteArray {
        val key = LedgerEd25519.algorandAccount(seed, index)
        try {
            return key.publicKey
        } finally {
            key.wipe()
        }
    }

    /** The address of a 32-byte public key: the 58 base32 characters that [AlgoAddress] writes. */
    fun addressOf(publicKey: ByteArray): String = AlgoAddress.encode(publicKey)
}
