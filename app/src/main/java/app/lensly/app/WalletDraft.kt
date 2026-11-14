package app.lensly.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lensly.core.wallet.EntropyMixer
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wallet being created, restored or imported, before it is saved. During a ceremony it holds the device's 256 bits,
 * and [wipe] zeroes them. The words and the passphrase are kept only until the wallet is saved or the flow is left. An
 * imported private key is held the same way, and so is its name.
 */
class WalletDraft {
    private var deviceEntropy: ByteArray? = null

    /** SHA-256 of the device's entropy, shown before the owner adds input. Empty when no ceremony is running. */
    var commitmentHex: String = ""
        private set

    /** SHA-256 of the device's entropy plus the owner's input. This is the entropy the words encode. Empty until mixed. */
    var mixedHashHex: String = ""
        private set

    /** The 24 words of the wallet, in buffers [wipe] zeroes. Null until they exist. */
    private var mnemonic: Mnemonic? = null

    /** The 24 words of the wallet. Empty until they exist. */
    val words: List<String> get() = mnemonic ?: emptyList()

    /** True when the words come from the device's randomness alone, because the owner skipped the ceremony. */
    var deviceGenerated: Boolean = false
        private set

    /**
     * True when the owner has backed up the words: the create ceremony's quiz has passed, or the words were typed in a restore. A save
     * writes it as the wallet's backed-up flag.
     */
    var backedUp: Boolean = false
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
            replaceMnemonic(Mnemonic.fromEntropy(mixed))
            deviceGenerated = false
            backedUp = false
        } finally {
            mixed.fill(0)
        }
    }

    /** Skips the ceremony. The words then come from the device's randomness alone. */
    fun skipCeremony() {
        val entropy = checkNotNull(deviceEntropy) { "No ceremony is running." }
        replaceMnemonic(Mnemonic.fromEntropy(entropy))
        deviceGenerated = true
        backedUp = false
        mixedHashHex = ""
    }

    /** Restore: the owner typed these words, so they count as backed up. */
    fun setTypedWords(typed: List<String>) {
        wipe()
        replaceMnemonic(Mnemonic.of(typed))
        backedUp = true
    }

    /** The create ceremony's quiz has passed: the owner has checked the words are written down. */
    fun markBackedUp() {
        backedUp = true
    }

    /** Replaces the words, zeroing the buffers of the previous ones. */
    private fun replaceMnemonic(next: Mnemonic) {
        mnemonic?.wipe()
        mnemonic = next
    }

    fun setPassphrase(value: String?) {
        passphrase = value
    }

    /** The private key of a Single Key import, checked and not yet saved. Null when no import is running. */
    var singleKey: ByteArray? = null
        private set

    /** The name of an imported wallet, given before its write-down. Empty until it is set. */
    var name: String = ""
        private set

    /** Takes ownership of [key], which [wipe] zeroes. A key held before is zeroed first. */
    fun setSingleKey(key: ByteArray) {
        singleKey?.fill(0)
        singleKey = key
    }

    fun setName(value: String) {
        name = value
    }

    /** Zeroes the device entropy, the words and the imported key, and drops everything else. */
    fun wipe() {
        deviceEntropy?.fill(0)
        deviceEntropy = null
        commitmentHex = ""
        mixedHashHex = ""
        mnemonic?.wipe()
        mnemonic = null
        deviceGenerated = false
        backedUp = false
        passphrase = null
        singleKey?.fill(0)
        singleKey = null
        name = ""
    }
}

/**
 * Writes a new wallet from the draft's words and passphrase, and returns the new wallet's id. The app's writer is
 * [WalletStore.create].
 */
typealias WalletWriter = suspend (
    name: String,
    deviceGenerated: Boolean,
    words: List<String>,
    passphrase: String?,
) -> String

/** Writes a new Single Key wallet from an imported private key, and returns the new wallet's id. */
typealias SingleKeyWriter = suspend (name: String, privateKey: ByteArray) -> String

/** Where the save of a draft's wallet is. A save that fails keeps the words, so the owner can save again. */
sealed interface SaveState {
    /** No save has run since the flow last ended. */
    data object Idle : SaveState

    /** The write is running. The screen holds its input until it ends. */
    data object Saving : SaveState

    /** The wallet was written as [walletId], and the draft's words were wiped once the write returned. */
    data class Saved(val walletId: String) : SaveState

    /** The write failed. The words are kept, so the owner can save again. */
    data object Failed : SaveState

    /** The vault closed during the write. The screen returns the owner to the camera. */
    data object Locked : SaveState
}

/**
 * Holds the [WalletDraft] for the hidden graph. It is scoped to that graph's back stack entry, so it survives
 * rotation, and it is cleared, wiping the draft, when the graph is left. A save runs in this model's scope, so the
 * screen that started it can leave without cancelling it.
 */
class WalletDraftViewModel : ViewModel() {
    val draft = WalletDraft()

    private val mutableSaveState = MutableStateFlow<SaveState>(SaveState.Idle)

    /** Where the save of the draft's wallet is. */
    val saveState: StateFlow<SaveState> = mutableSaveState.asStateFlow()

    /**
     * Saves the draft's wallet through [write], off the main thread. The write gets a copy of the words, taken before it
     * starts, so no wipe can change them while it runs. The draft's words are wiped as soon as [write] returns, in the same
     * coroutine. A failed write keeps them, and [save] may run again. A call made while a save runs, or with no words,
     * does nothing.
     */
    fun save(name: String, write: WalletWriter) {
        if (mutableSaveState.value == SaveState.Saving || draft.words.isEmpty()) return
        val words = draft.words.toList()
        val deviceGenerated = draft.deviceGenerated
        val passphrase = draft.passphrase
        launchSave { write(name, deviceGenerated, words, passphrase) }
    }

    /**
     * Saves the draft's Single Key wallet through [write], with the rules of [save]. The write gets a copy of the key, which
     * is zeroed when the write returns, so a wipe during the write cannot change the key it writes. The draft's key is wiped
     * once the write has returned. A call made while a save runs, or with no key, does nothing.
     */
    fun saveSingleKey(name: String, write: SingleKeyWriter) {
        val key = draft.singleKey ?: return
        if (mutableSaveState.value == SaveState.Saving) return
        val copy = key.copyOf()
        launchSave {
            try {
                write(name, copy)
            } finally {
                copy.fill(0)
            }
        }
    }

    /** Runs [write] off the main thread in the model's scope, and sets the save state from its outcome. */
    private fun launchSave(write: suspend () -> String) {
        mutableSaveState.value = SaveState.Saving
        viewModelScope.launch {
            mutableSaveState.value = try {
                val walletId = withContext(Dispatchers.IO) { write() }
                draft.wipe()
                SaveState.Saved(walletId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // A closed session or a damaged vault, which whileUnlocked also treats as a return to the camera.
                SaveState.Locked
            } catch (e: Exception) {
                SaveState.Failed
            }
        }
    }

    /**
     * Returns the save state to [SaveState.Idle], unless a save is running. A flow's HOME screen calls it when the flow ends,
     * so a finished save does not act again in a later flow. A running save keeps its state, so its result is still kept.
     */
    fun clearSaveState() {
        if (mutableSaveState.value != SaveState.Saving) mutableSaveState.value = SaveState.Idle
    }

    override fun onCleared() {
        draft.wipe()
    }
}
