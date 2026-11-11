package app.lensly.ui.flow

import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The part of a wallet's secret on screen. A Mnemonic wallet shows its words, and then its passphrase when it has one.
 * A Single Key shows its key.
 */
enum class RevealPart { WORDS, PASSPHRASE, KEY }

/**
 * A secret while it is on screen, one page at a time. A page is one word, one passphrase word, or the whole key.
 *
 * The words are held in a [Mnemonic], which [wipe] zeroes. The passphrase and the key's hex are Strings, which cannot
 * be zeroed, so their references are dropped instead, as [app.lensly.app.WalletStore] does. A Single Key's bytes are
 * zeroed as soon as its hex has been built. [toString] never shows the secret.
 */
class RevealedSecret private constructor(
    private var words: Mnemonic?,
    private var passphraseWords: List<String>,
    private var keyHex: String?,
) {
    /** True once [wipe] has run. A wiped secret has no pages. */
    var isWiped: Boolean = false
        private set

    /** True when a Mnemonic wallet has a passphrase to show. */
    val hasPassphrase: Boolean get() = passphraseWords.isNotEmpty()

    /** True for a Single Key, whose only part is [RevealPart.KEY]. */
    val isKey: Boolean get() = keyHex != null

    /** The number of pages in [part]. */
    fun pageCount(part: RevealPart): Int = when (part) {
        RevealPart.WORDS -> words?.size ?: 0
        RevealPart.PASSPHRASE -> passphraseWords.size
        RevealPart.KEY -> if (keyHex == null) 0 else 1
    }

    /**
     * The text of page [index] of [part]. The passphrase is split on its spaces, and an empty word between two spaces
     * shows as [EMPTY_WORD]. Each word is returned as it was typed, so its letter case is kept.
     */
    fun page(part: RevealPart, index: Int): String = when (part) {
        RevealPart.WORDS -> words?.get(index) ?: throw IndexOutOfBoundsException("index $index, size 0")
        RevealPart.PASSPHRASE -> passphraseWords[index].ifEmpty { EMPTY_WORD }
        RevealPart.KEY -> keyHex.orEmpty()
    }

    /** Zeroes the words and drops the rest of the secret. Nothing can be shown from it afterwards. */
    fun wipe() {
        words?.wipe()
        words = null
        passphraseWords = emptyList()
        keyHex = null
        isWiped = true
    }

    override fun toString(): String = "RevealedSecret(redacted)"

    companion object {
        private const val EMPTY_WORD = "(empty)"

        /**
         * A Mnemonic wallet's words and passphrase. The words go into a [Mnemonic] of their own, which the secret holds
         * and wipes; the caller keeps its list. An empty passphrase counts as none, as it does for the seed.
         */
        fun ofMnemonic(words: List<String>, passphrase: String?): RevealedSecret = RevealedSecret(
            words = Mnemonic.of(words),
            passphraseWords = if (passphrase.isNullOrEmpty()) emptyList() else passphrase.split(' '),
            keyHex = null,
        )

        /** A Single Key, shown as hex. [privateKey] is zeroed here, so the caller keeps no copy of it. */
        fun ofKey(privateKey: ByteArray): RevealedSecret = try {
            RevealedSecret(words = null, passphraseWords = emptyList(), keyHex = privateKey.toHex())
        } finally {
            privateKey.fill(0)
        }
    }
}

/** Where the Show Secret screen is at each point. */
sealed interface RevealStep {
    /** The App Password is asked. [busy] while the check runs. A wrong password returns here with nothing shown. */
    data class Password(val busy: Boolean) : RevealStep

    /** The App Password was right. Nothing is read yet. The owner reads the warning and presses SHOW. */
    data object Warning : RevealStep

    /** The secret is on screen. [secondsLeft] counts down to the timeout. */
    data class Revealed(
        val secret: RevealedSecret,
        val part: RevealPart,
        val index: Int,
        val secondsLeft: Int,
    ) : RevealStep

    /** The secret is gone, and the screen leaves. [reason] says why. */
    data class Cleared(val reason: ClearReason) : RevealStep
}

/** Why the secret left the screen. */
enum class ClearReason {
    /** The countdown ran out. */
    TIMEOUT,

    /** The owner pressed Done, Hide or Back. */
    DONE,

    /** The owner pressed Lock, or the app left the foreground. The lock takes the app to the camera. */
    LOCKED,

    /** The vault closed while the secret was being read. The gate takes over. */
    VAULT_CLOSED,

    /** The vault could not be read or checked. */
    UNAVAILABLE,
}

/** The result of reading the secret from the open vault. */
sealed interface SecretLoad {
    class Loaded(val secret: RevealedSecret) : SecretLoad

    /** The vault was locked before the secret could be read. */
    data object Locked : SecretLoad

    /** The wallet or its record cannot be read. */
    data object Unavailable : SecretLoad
}

/** What [RevealFlow] asks of the app. [VaultRevealPorts] is the app's implementation, and tests use fakes. */
interface RevealPorts {
    /**
     * Checks the App Password through the gate's unlock, so the Failure Delay applies. The caller keeps ownership of
     * [password] and zeroes it afterwards.
     */
    suspend fun checkPassword(password: CharArray): PasswordCheck

    /** Reads the secret of the wallet from the open vault. */
    suspend fun loadSecret(): SecretLoad
}

/**
 * Show Secret as a state machine. The owner re-enters the App Password, reads a warning, and then sees the secret one page
 * at a time. The secret is cleared when the countdown ends, when the owner leaves, and when the screen is left some other
 * way. It has no Android types, and [RevealPorts] does the vault work.
 *
 * The countdown runs for [TIMEOUT_MS] after the last step, so each page change restarts it. [tick] advances the countdown.
 * [leave] clears the secret at once. Work still running at that moment changes nothing, and any secret it had read is
 * wiped.
 */
class RevealFlow(
    private val ports: RevealPorts,
    private val nowMs: () -> Long,
) {
    private val gate = Mutex()
    private val current = MutableStateFlow<RevealStep>(RevealStep.Password(busy = false))

    /** Where the screen is now. */
    val state: StateFlow<RevealStep> = current.asStateFlow()

    /** Raised by [end]. A call that started under an earlier value must not change the state. */
    private var epoch = 0

    /** The time at which the secret is cleared, unless a step restarts the countdown first. */
    private var deadlineMs = 0L

    /**
     * The owner typed the App Password. A wrong one, or one refused during a Failure Delay, leaves the step as it was with
     * nothing shown. A right one moves on to the warning. [password] is zeroed before this returns.
     */
    suspend fun submitPassword(password: CharArray) {
        try {
            gate.withLock {
                val step = current.value as? RevealStep.Password ?: return
                if (step.busy) return
                val started = epoch
                current.value = RevealStep.Password(busy = true)
                val next = when (ports.checkPassword(password)) {
                    PasswordCheck.Accepted -> RevealStep.Warning
                    PasswordCheck.Rejected -> RevealStep.Password(busy = false)
                    PasswordCheck.Unavailable -> RevealStep.Cleared(ClearReason.UNAVAILABLE)
                }
                publish(started, next)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** SHOW on the warning. Reads the secret and shows its first page: the first word, or the key. */
    suspend fun show() {
        gate.withLock {
            if (current.value != RevealStep.Warning) return
            val started = epoch
            when (val loaded = ports.loadSecret()) {
                is SecretLoad.Loaded -> {
                    if (started != epoch) {
                        loaded.secret.wipe()
                        return
                    }
                    val part = if (loaded.secret.isKey) RevealPart.KEY else RevealPart.WORDS
                    restartCountdown()
                    current.value = RevealStep.Revealed(loaded.secret, part, index = 0, secondsLeft = TIMEOUT_SECONDS)
                }
                SecretLoad.Locked -> publish(started, RevealStep.Cleared(ClearReason.VAULT_CLOSED))
                SecretLoad.Unavailable -> publish(started, RevealStep.Cleared(ClearReason.UNAVAILABLE))
            }
        }
    }

    /** Next (+1) or previous (-1) page of the shown part. Pages do not wrap. Each move restarts the countdown. */
    fun move(delta: Int) {
        val shown = current.value as? RevealStep.Revealed ?: return
        val last = (shown.secret.pageCount(shown.part) - 1).coerceAtLeast(0)
        restartCountdown()
        current.value = shown.copy(
            index = (shown.index + delta).coerceIn(0, last),
            secondsLeft = TIMEOUT_SECONDS,
        )
    }

    /** Switches a Mnemonic wallet to its words or its passphrase, from the first page. Restarts the countdown. */
    fun select(part: RevealPart) {
        val shown = current.value as? RevealStep.Revealed ?: return
        if (shown.secret.isKey || part == RevealPart.KEY) return
        if (part == RevealPart.PASSPHRASE && !shown.secret.hasPassphrase) return
        restartCountdown()
        current.value = shown.copy(part = part, index = 0, secondsLeft = TIMEOUT_SECONDS)
    }

    /** One tick of the countdown. The secret is cleared when the countdown has run out. */
    fun tick() {
        val shown = current.value as? RevealStep.Revealed ?: return
        val remaining = deadlineMs - nowMs()
        if (remaining <= 0L) {
            end(ClearReason.TIMEOUT)
            return
        }
        val seconds = ((remaining + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt()
        if (seconds != shown.secondsLeft) current.value = shown.copy(secondsLeft = seconds)
    }

    /** Leaves the screen with [reason]. The secret is cleared at once, and a later call changes nothing. */
    fun leave(reason: ClearReason = ClearReason.DONE) {
        if (current.value is RevealStep.Cleared) return
        end(reason)
    }

    /** Clears the secret and moves to [RevealStep.Cleared]. Any call still running stops publishing. */
    private fun end(reason: ClearReason) {
        epoch++
        (current.value as? RevealStep.Revealed)?.secret?.wipe()
        current.value = RevealStep.Cleared(reason)
    }

    private fun restartCountdown() {
        deadlineMs = nowMs() + TIMEOUT_MS
    }

    private fun publish(started: Int, step: RevealStep) {
        if (started == epoch) current.value = step
    }

    companion object {
        /** How long a secret stays on screen after the last step. */
        const val TIMEOUT_MS = 60_000L

        private const val TIMEOUT_SECONDS = 60
        private const val MS_PER_SECOND = 1_000L
    }
}
