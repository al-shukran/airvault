package app.lensly.app

import androidx.lifecycle.ViewModel
import app.lensly.core.wallet.EntropyMixer
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import java.security.SecureRandom

/**
 * The wallet being created or restored, before it is saved. During a ceremony it holds the device's 256 bits,
 * and [wipe] zeroes them. The words and the passphrase are kept only until the wallet is saved or the flow is left.
 */
class WalletDraft {
    private var deviceEntropy: ByteArray? = null

    /** SHA-256 of the device's entropy, shown before the owner adds input. Empty when no ceremony is running. */
    var commitmentHex: String = ""
        private set

    /** SHA-256 of the device's entropy plus the owner's input. This is the entropy the words encode. Empty until mixed. */
    var mixedHashHex: String = ""
        private set

    /** The 24 words of the wallet. Empty until they exist. */
    var words: List<String> = emptyList()
        private set

    /** True when the words come from the device's randomness alone, because the owner skipped the ceremony. */
    var deviceGenerated: Boolean = false
        private set

    /** The optional BIP39 passphrase. Null means none. */
    var passphrase: String? = null
        private set

    /** The last word, which carries the checksum. Empty until the words exist. */
    val checksumWord: String get() = words.lastOrNull().orEmpty()

    /** Starts a ceremony with fresh device randomness, and publishes its commitment. */
    fun startCeremony() {
        wipe()
        val entropy = ByteArray(Mnemonic.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        deviceEntropy = entropy
        commitmentHex = EntropyMixer.commitment(entropy)
    }

    /**
     * Mixes the owner's input (dice digits 1 to 6, or coin letters H and T) into the device's entropy and derives
     * the words. Mixing again with different input replaces the words, so the owner can redo the roll.
     */
    fun mixInput(input: String) {
        val entropy = checkNotNull(deviceEntropy) { "No ceremony is running." }
        val mixed = EntropyMixer.mix(entropy, input)
        try {
            mixedHashHex = mixed.toHex()
            words = Mnemonic.fromEntropy(mixed)
            deviceGenerated = false
        } finally {
            mixed.fill(0)
        }
    }

    /** Skips the ceremony. The words then come from the device's randomness alone. */
    fun skipCeremony() {
        val entropy = checkNotNull(deviceEntropy) { "No ceremony is running." }
        words = Mnemonic.fromEntropy(entropy)
        deviceGenerated = true
        mixedHashHex = ""
    }

    /** Restore: the owner typed these words. */
    fun setTypedWords(typed: List<String>) {
        wipe()
        words = typed
    }

    fun setPassphrase(value: String?) {
        passphrase = value
    }

    /** Zeroes the device entropy and drops everything else. */
    fun wipe() {
        deviceEntropy?.fill(0)
        deviceEntropy = null
        commitmentHex = ""
        mixedHashHex = ""
        words = emptyList()
        deviceGenerated = false
        passphrase = null
    }
}

/**
 * Holds the [WalletDraft] for the hidden graph. It is scoped to that graph's back stack entry, so it survives
 * rotation, and it is cleared, wiping the draft, when the graph is left.
 */
class WalletDraftViewModel : ViewModel() {
    val draft = WalletDraft()

    override fun onCleared() {
        draft.wipe()
    }
}
