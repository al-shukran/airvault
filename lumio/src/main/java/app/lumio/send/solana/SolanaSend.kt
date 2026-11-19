package app.lumio.send.solana

import app.airvault.chains.solana.signer.SolCluster
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.lensly.app.export.partsOf
import app.lensly.core.solana.SolanaNonce
import app.lensly.core.solana.SolanaTransaction
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequestEncoder
import app.lensly.core.ur.SolSignature
import app.lensly.core.wallet.Ed25519Signer
import app.lumio.data.Clock
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.RecentBlockhash
import app.lumio.data.provider.SolanaAccountState
import app.lumio.data.provider.SolanaBlockHeight
import app.lumio.data.provider.SolanaSignatureStatus
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom

/** The fee of a one-signature transfer: 5000 lamports. */
const val SOLANA_FEE_LAMPORTS = 5_000L

/** The rent-exempt reserve a send leaves in the sender: 0.00089088 SOL, which is 890880 lamports. */
const val SOLANA_RENT_RESERVE_LAMPORTS = 890_880L

/** The origin Lumio names in its sign request. */
private const val ORIGIN = "Lumio"

/** How many times the status is read after a send, and the wait between two reads. Solana confirms within seconds, so this is about a minute. */
private const val STATUS_POLLS = 30
private const val STATUS_POLL_MS = 2_000L

/** The Solana account a send comes from: N of m/44'/501'/N', its public key, and the fingerprint of the wallet's root key. */
class SolanaSendAccount(val index: Int, val key: ByteArray, val sourceFingerprint: Int?) {
    /** The account's address, in base58. */
    val address: String get() = Base58.encode(key)
}

/**
 * When a setup's blockhash stops being valid: the last block height at which it is accepted, and the [MonotonicClock][app.lumio.data.MonotonicClock]
 * milliseconds at which Lumio fetched it. A setup is refused once the chain's block height passes [lastValidBlockHeight].
 */
class SetupWindow(val lastValidBlockHeight: Long, val fetchedAtMs: Long)

/** Thrown before a signed setup is sent, when the chain has passed its blockhash's last valid height. The network would refuse it, so it is not sent. */
class BlockhashExpired : Exception(SETUP_EXPIRED)

/**
 * A send from one Solana account, as far as Lumio can build it without a key: the legacy message that Lensly signs, the blockhash it names, and the
 * request ID that Lensly's answer must echo. [lamports] goes to [recipient] and [feeLamports] is the network fee. A one-time setup carries its
 * [window]; a send that names a nonce does not expire, so it has none.
 */
class SolanaSendPlan(
    val network: NetworkId,
    val account: SolanaSendAccount,
    val recipient: ByteArray,
    val lamports: Long,
    val feeLamports: Long,
    val blockhash: ByteArray,
    val requestId: ByteArray,
    val message: ByteArray,
    val window: SetupWindow? = null,
    /** The SPL token that the send moves, or null for a SOL send. A token send moves [SplSendToken.amount] and no lamports. */
    val token: SplSendToken? = null,
) {
    /** The sender's address, in base58. */
    val senderAddress: String get() = account.address

    /** The recipient's address, in base58. */
    val recipientAddress: String get() = Base58.encode(recipient)
}

/** A signed transaction, as `sendTransaction` takes it, and the signature the network must report for it. */
class SolanaSignedSend(val raw: ByteArray, val signature: String)

/**
 * The nonce that a send names: the one-time setup while the account has no nonce account, or the nonce value that an initialized nonce account of the
 * account holds. A [Durable] send names no blockhash that ages out, so it stays valid until its nonce moves.
 */
sealed interface SolanaNoncePath {
    data object Setup : SolanaNoncePath

    class Durable(val nonceValue: ByteArray) : SolanaNoncePath
}

/** What the owner reads when the one-time setup is refused because its blockhash has aged out. Nothing was created, so the setup is started again. */
internal const val SETUP_EXPIRED = "The setup is too old for the network, so no nonce account was made. Start the setup again and sign it within about 20 seconds."

/** What the owner reads when a send is refused because the nonce it names has moved since the send was prepared. */
internal const val NONCE_MOVED = "The network refused the send: this account's nonce changed after the send was prepared. Make the send again."

/** What the owner reads when the account at the nonce address is not an initialized nonce account of this account. */
internal const val NONCE_UNUSABLE = "The nonce account of this account cannot be used for a send."

/** How a send ended on the network, as far as the status reads showed. [PENDING] means no read showed the answer in time. */
enum class SolanaOutcome { CONFIRMED, FAILED, PENDING }

/**
 * A send from one Solana account. Lumio reads the balance and a recent blockhash, builds the legacy message of the transfer, and holds no key. It
 * broadcasts only a signature that Lensly made for this message, after the signature has been checked against the account's public key.
 */
object SolanaSend {
    private val random = SecureRandom()

    /** The most that can be sent from [balance]: the balance less the fee and the rent reserve. Null when the balance does not cover both. */
    fun maxLamports(balance: BigInteger): Long? {
        val spendable = balance - BigInteger.valueOf(SOLANA_FEE_LAMPORTS + SOLANA_RENT_RESERVE_LAMPORTS)
        if (spendable.signum() <= 0) return null
        return spendable.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
    }

    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    /**
     * The nonce path of a send from [account], given the state of its nonce account. While no nonce account exists the path is [SolanaNoncePath.Setup].
     * Otherwise the nonce account must be the System Program's and name [account] as its authority, and the path is [SolanaNoncePath.Durable] with the
     * nonce value that it holds. Throws IllegalArgumentException, with the owner's words, for any other account.
     */
    fun noncePath(state: SolanaAccountState?, account: ByteArray): SolanaNoncePath {
        if (state == null) return SolanaNoncePath.Setup
        val nonceValue = state.takeIf { it.owner == SYSTEM_PROGRAM }?.let { SolanaNonce.nonceValue(it.data, account) }
            ?: throw IllegalArgumentException(NONCE_UNUSABLE)
        return SolanaNoncePath.Durable(nonceValue)
    }

    /** A fresh 16-byte request ID. Lensly's answer must echo it, so an old answer cannot be used for this send. */
    fun newRequestId(): ByteArray = ByteArray(REQUEST_ID_BYTES).also { random.nextBytes(it) }

    /**
     * The send of [lamports] from [account] to [recipient], with [balance] in the account, as a transfer that advances the account's nonce.
     * [nonceValue] is the nonce that the account's nonce account holds, and the message names it as its recent blockhash. Throws
     * IllegalArgumentException, with the owner's words, when the amount is not positive or the balance does not cover the amount, the fee and the
     * rent reserve.
     */
    fun plan(
        network: NetworkId,
        account: SolanaSendAccount,
        recipient: ByteArray,
        lamports: Long,
        balance: BigInteger,
        nonceValue: ByteArray,
        requestId: ByteArray,
    ): SolanaSendPlan {
        require(lamports > 0) { "The amount must be more than 0 SOL." }
        val needed = BigInteger.valueOf(lamports) + BigInteger.valueOf(SOLANA_FEE_LAMPORTS + SOLANA_RENT_RESERVE_LAMPORTS)
        require(balance >= needed) { "This account does not hold enough SOL for the amount, the fee and the rent reserve." }
        return SolanaSendPlan(
            network = network,
            account = account,
            recipient = recipient,
            lamports = lamports,
            feeLamports = SOLANA_FEE_LAMPORTS,
            blockhash = nonceValue,
            requestId = requestId,
            message = SolanaNonce.transferMessage(account.key, recipient, lamports, nonceValue),
        )
    }

    /**
     * Checks that [balance] covers the one-time setup of a nonce account, which costs [rentLamports] of rent: the rent, the fee and the rent
     * reserve. Throws IllegalArgumentException, with the owner's words, when it does not.
     */
    fun checkSetupFunds(balance: BigInteger, rentLamports: Long) {
        val needed = BigInteger.valueOf(rentLamports) + BigInteger.valueOf(SOLANA_FEE_LAMPORTS + SOLANA_RENT_RESERVE_LAMPORTS)
        require(balance >= needed) { "This account does not hold enough SOL for the nonce account, the fee and the rent reserve." }
    }

    /**
     * The one-time setup of [account]'s nonce account, at [blockhash], which is valid until [window] says. It pays [rentLamports] of rent into the
     * nonce account, which is the plan's recipient. Throws IllegalArgumentException, as [checkSetupFunds] does.
     */
    fun setupPlan(
        network: NetworkId,
        account: SolanaSendAccount,
        rentLamports: Long,
        balance: BigInteger,
        blockhash: ByteArray,
        requestId: ByteArray,
        window: SetupWindow,
    ): SolanaSendPlan {
        checkSetupFunds(balance, rentLamports)
        return SolanaSendPlan(
            network = network,
            account = account,
            recipient = SolanaNonce.accountOf(account.key),
            lamports = rentLamports,
            feeLamports = SOLANA_FEE_LAMPORTS,
            blockhash = blockhash,
            requestId = requestId,
            message = SolanaNonce.setupMessage(account.key, blockhash, rentLamports),
            window = window,
        )
    }

    /**
     * The one-time setup of [account] at the latest blockhash of [network], as the chain confirms it. The fetch time is read before the calls, so a
     * slow answer never makes the window look longer than it is.
     */
    suspend fun setupAt(scheduler: Scheduler, network: NetworkId, account: SolanaSendAccount, rentLamports: Long, clock: Clock): SolanaSendPlan {
        val fetchedAtMs = clock.nowMs()
        val latest = scheduler.run(RecentBlockhash(network), Priority.FOREGROUND)
        val available = scheduler.run(NativeBalance(network, account.address), Priority.FOREGROUND)
        return setupPlan(
            network = network,
            account = account,
            rentLamports = rentLamports,
            balance = available,
            blockhash = Base58.decode(latest.blockhash),
            requestId = newRequestId(),
            window = SetupWindow(latest.lastValidBlockHeight, fetchedAtMs),
        )
    }

    /** True once [height] has passed [lastValidBlockHeight]: a blockhash is accepted at its last valid height and no later. */
    fun expired(height: Long, lastValidBlockHeight: Long): Boolean = height > lastValidBlockHeight

    /**
     * Reads the block height of [network] before a setup is sent, and throws [BlockhashExpired] when [window] has run out, so nothing is sent. A
     * failed read throws its [ProviderError], and the same signed setup can be sent again.
     */
    suspend fun requireUnexpired(scheduler: Scheduler, network: NetworkId, window: SetupWindow) {
        val height = scheduler.run(SolanaBlockHeight(network), Priority.FOREGROUND)
        if (expired(height, window.lastValidBlockHeight)) throw BlockhashExpired()
    }

    /** How long a one-time setup may stay on the screen unscanned, counted from its fetch, before Lumio rebuilds it at a fresh blockhash. */
    const val SETUP_REFRESH_MS = 20_000L

    /** The wait until a setup fetched at [fetchedAtMs] is due for a rebuild, when the clock reads [nowMs]. Zero once it is due. */
    fun refreshWait(fetchedAtMs: Long, nowMs: Long): Long = (fetchedAtMs + SETUP_REFRESH_MS - nowMs).coerceAtLeast(0L)

    /** Waits on [pause] until a setup fetched at [fetchedAtMs] is due for a rebuild. It returns at once when it already is. */
    suspend fun awaitRefresh(fetchedAtMs: Long, clock: Clock, pause: suspend (Long) -> Unit = { delay(it) }) {
        pause(refreshWait(fetchedAtMs, clock.nowMs()))
    }

    /**
     * The sign request of [plan] as the frames of an animated code. It names the account's path m/44'/501'/N' and its key, so Lensly refuses a
     * request for another account. It also states the cluster of the plan's network, which Lensly shows as stated by Lumio.
     */
    fun requestParts(plan: SolanaSendPlan): List<String> {
        val request = SolSignRequest(
            requestId = plan.requestId,
            signData = plan.message,
            derivationPath = listOf(PathStep(PURPOSE, true), PathStep(SOLANA_COIN_TYPE, true), PathStep(plan.account.index, true)),
            sourceFingerprint = plan.account.sourceFingerprint,
            address = plan.account.key,
            origin = ORIGIN,
            signType = SolSignType.TRANSACTION,
            cluster = requireNotNull(SolCluster.fromWire(plan.network.name)) {
                "Lumio has no Solana cluster named ${plan.network.name}."
            },
        )
        return partsOf(SolSignRequestEncoder.encode(request))
    }

    /**
     * The signed transaction that [ur] carries for [plan]. The answer must be a sol-signature for this request, and its signature must verify under the
     * account's key over the plan's message. Nothing is signed or sent for an answer that fails either check. Throws IllegalArgumentException, with
     * the owner's words.
     */
    fun signed(plan: SolanaSendPlan, ur: UR): SolanaSignedSend {
        val answer = when (val decoded = SolSignature.decode(ur)) {
            is SolSignature.Result.Ok -> decoded.signature
            is SolSignature.Result.Rejected -> throw IllegalArgumentException("This code is not a signature from Lensly.")
        }
        require(answer.requestId.contentEquals(plan.requestId)) { "This signature is for a different request." }
        require(Ed25519Signer.verify(plan.account.key, plan.message, answer.signature)) {
            "This signature does not match the account. Scan the signature that Lensly shows for this send."
        }
        return SolanaSignedSend(
            raw = SolanaTransaction.signed(listOf(answer.signature), plan.message),
            signature = Base58.encode(answer.signature),
        )
    }

    /**
     * Sends [signed] and returns its signature. The network must report the signature of [signed], so the owner is never sent to a transaction that
     * Lumio did not sign. The call is routed by the scheduler, as every Solana call is.
     */
    suspend fun broadcast(scheduler: Scheduler, network: NetworkId, signed: SolanaSignedSend): String {
        val reported = scheduler.run(Broadcast(network, signed.raw), Priority.FOREGROUND)
        require(reported == signed.signature) { "The server reported a different signature. Check the explorer before you send again." }
        return reported
    }

    /**
     * Reads the status of [signature] until the network confirms it or fails it, or until the polls run out. A read that fails is tried again on the next
     * poll. [pause] waits between two polls, so tests run in no real time.
     */
    suspend fun confirm(
        scheduler: Scheduler,
        network: NetworkId,
        signature: String,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): SolanaOutcome {
        repeat(STATUS_POLLS) { poll ->
            val status = try {
                scheduler.run(SolanaSignatureStatus(network, signature), Priority.FOREGROUND)
            } catch (e: ProviderError) {
                null
            }
            if (status != null && status.failed) return SolanaOutcome.FAILED
            if (status != null && status.confirmed) return SolanaOutcome.CONFIRMED
            if (poll < STATUS_POLLS - 1) pause(STATUS_POLL_MS)
        }
        return SolanaOutcome.PENDING
    }

    /** The explorer page of [signature] on [network]: devnet names its cluster, and mainnet-beta is the default cluster, so it names none. */
    fun explorerTxUrl(network: NetworkId, signature: String): String =
        "https://explorer.solana.com/tx/$signature" + if (network == SOLANA_MAINNET) "" else "?cluster=devnet"

    private const val REQUEST_ID_BYTES = 16
    private const val PURPOSE = 44
    private const val SOLANA_COIN_TYPE = 501
}

/**
 * The owner's inputs for a send, checked. Each check throws IllegalArgumentException with the words the form shows, and an amount is read as an exact
 * count of lamports, never through a float.
 */
object SolanaSendInput {
    private const val MAX_AMOUNT_CHARS = 20
    private const val SOL_DECIMALS = 9
    private val BASE58 = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")
    private val DECIMAL = Regex("\\d+(\\.\\d+)?")

    /** The recipient as its 32 bytes. [text] is a base58 address, or a `solana:` link that holds one; any query after the address is ignored. */
    fun recipient(text: String): ByteArray = Base58.decode(recipientAddress(text))

    /** The recipient address in base58, when [text] is an address or a `solana:` link that holds one. Throws IllegalArgumentException otherwise. */
    fun recipientAddress(text: String): String {
        val address = text.trim().removePrefix("solana:").substringBefore('?').trim()
        require(address.isNotEmpty()) { "Enter the recipient address." }
        require(BASE58.matches(address)) { "Enter a Solana address." }
        require(Base58.decode(address).size == 32) { "Enter a Solana address." }
        return address
    }

    /** The amount of [text] in SOL, in lamports. It has at most 9 decimal places and is more than zero. */
    fun lamports(text: String): Long {
        val amount = text.trim()
        require(amount.length <= MAX_AMOUNT_CHARS && DECIMAL.matches(amount)) { "Enter an amount in SOL, such as 0.1." }
        val value = BigDecimal(amount)
        require(value.stripTrailingZeros().scale() <= SOL_DECIMALS) { "Use at most 9 decimal places for SOL." }
        val lamports = value.movePointRight(SOL_DECIMALS).toBigIntegerExact()
        require(lamports.signum() > 0) { "The amount must be more than 0 SOL." }
        require(lamports <= BigInteger.valueOf(Long.MAX_VALUE)) { "The amount is more than all the SOL there is." }
        return lamports.toLong()
    }

    /** The base units of [text] in a token with [decimals] places: a plain amount with at most that many decimal places, above zero. */
    fun tokenUnits(text: String, decimals: Int): BigInteger {
        val amount = text.trim()
        require(amount.length <= MAX_AMOUNT_CHARS && DECIMAL.matches(amount)) { "Enter an amount, such as 1.5." }
        val value = BigDecimal(amount)
        require(value.stripTrailingZeros().scale() <= decimals) { "Use at most $decimals decimal places for this token." }
        val units = value.movePointRight(decimals).toBigIntegerExact()
        require(units.signum() > 0) { "The amount must be more than 0." }
        require(units.bitLength() < Long.SIZE_BITS) { "The amount is more than all of this token there is." }
        return units
    }

    /** [lamports] in SOL, as the form shows it: plain digits with no trailing zeros. */
    fun format(lamports: BigInteger): String = BigDecimal(lamports, SOL_DECIMALS).stripTrailingZeros().toPlainString()
}
