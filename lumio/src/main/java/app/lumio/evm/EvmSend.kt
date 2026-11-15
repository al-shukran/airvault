package app.lumio.evm

import app.lensly.core.evm.TxSigner
import app.lensly.core.evm.TxType
import app.lensly.core.evm.UnsignedTx
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.EthSignature
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import com.sparrowwallet.hummingbird.UR
import java.math.BigDecimal
import java.math.BigInteger
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/**
 * A native-coin send as far as Lumio can build it without a key: the inputs the owner gave, checked, and the unsigned EIP-1559 transaction
 * they make. The wallet signs [payload]. Lumio holds no key, so it can only show the request and check the answer.
 */
class PreparedSend(
    val chain: EvmChain,
    /** The address index of the sending account: the account path is m/44'/60'/0'/0/[index]. */
    val index: Int,
    /** The EIP-55 address that sends. */
    val from: String,
    /** The EIP-55 address that receives. */
    val to: String,
    /** The amount in wei. */
    val valueWei: BigInteger,
    /** The nonce: the pending transaction count of [from]. */
    val nonce: Long,
    /** The gas limit. */
    val gasLimit: BigInteger,
    /** The fee cap per gas, in wei. */
    val maxFeePerGas: BigInteger,
    /** The priority fee per gas, in wei. */
    val maxPriorityFeePerGas: BigInteger,
    /** The native balance of [from] at the read, in wei. */
    val balanceWei: BigInteger,
) {
    /** The unsigned transaction. */
    val tx: UnsignedTx = UnsignedTx(
        type = TxType.EIP1559,
        chainId = BigInteger.valueOf(chain.chainId),
        nonce = BigInteger.valueOf(nonce),
        to = to,
        value = valueWei,
        data = "0x",
        gasLimit = gasLimit,
        gasPrice = null,
        maxPriorityFeePerGas = maxPriorityFeePerGas,
        maxFeePerGas = maxFeePerGas,
    )

    /** The payload that the wallet signs: [tx] in the form the Lensly parser reads back. */
    val payload: ByteArray = checkNotNull(tx.encode()) { "The transaction is not in canonical form." }

    /** The most the gas can cost, at the fee cap, in wei. */
    val maxFeeWei: BigInteger get() = gasLimit * maxFeePerGas

    /** The most the send can cost: the amount and the gas at the fee cap, in wei. */
    val maxCostWei: BigInteger get() = valueWei + maxFeeWei
}

/** A send from one address of a paired wallet, as the owner builds it, and the sign request and answer that go with it. */
object EvmSend {
    /** The gas of a plain transfer. It is the fallback when the endpoint refuses to estimate the gas. */
    const val TRANSFER_GAS = 21_000L

    /** The origin that the sign request names, so Lensly's review shows which app asked. */
    const val ORIGIN = "Lumio"

    private val TRANSFER = BigInteger.valueOf(TRANSFER_GAS)
    private val TWO = BigInteger.valueOf(2)

    /** The account path m/44'/60'/0'/0/[index] that a sign request names. */
    fun derivationPath(index: Int): List<PathStep> = listOf(
        PathStep(44, true),
        PathStep(60, true),
        PathStep(0, true),
        PathStep(0, false),
        PathStep(index, false),
    )

    /**
     * Reads what a transfer of [valueWei] from [from] needs on [chain], and builds it: the balance, the pending nonce, the priority fee, a fee
     * cap of twice the next base fee plus the priority fee, and the gas. Throws IllegalArgumentException, or [RpcFailure], with the owner's
     * words. It blocks on the endpoint, so the caller runs it off the main thread.
     */
    fun prepare(
        rpc: EvmRpc,
        url: String,
        chain: EvmChain,
        from: String,
        index: Int,
        to: String,
        valueWei: BigInteger,
    ): PreparedSend {
        rpc.requireChain(url, chain.chainId)
        val balance = rpc.balance(url, from)
        val nonce = rpc.pendingTransactionCount(url, from)
        val priority = rpc.maxPriorityFee(url)
        val feeCap = rpc.baseFee(url) * TWO + priority
        val gas = gasFor(rpc, url, from, to, valueWei)
        val send = PreparedSend(chain, index, from, to, valueWei, nonce, gas, feeCap, priority, balance)
        require(send.maxCostWei <= balance) {
            "The balance is ${EvmFormat.amount(balance, chain.decimals, chain.symbol)}, but this send can cost up to " +
                EvmFormat.amount(send.maxCostWei, chain.decimals, chain.symbol) + "."
        }
        return send
    }

    /** The sign request for [send], for the wallet with master fingerprint [fingerprint], under the request ID [requestId]. */
    fun request(send: PreparedSend, fingerprint: Long, requestId: ByteArray): EthSignRequest = EthSignRequest(
        requestId = requestId,
        signData = send.payload,
        dataType = DataType.TYPED_TRANSACTION,
        chainId = send.chain.chainId,
        derivationPath = derivationPath(send.index),
        sourceFingerprint = fingerprint.toInt(),
        address = Numeric.hexStringToByteArray(send.from),
        origin = ORIGIN,
    )

    /**
     * The raw signed transaction that [ur] answers for [send]. Throws IllegalArgumentException, with the owner's words, when [ur] is not
     * a signature for this request: another type, another request ID, a signature that is not an EIP-1559 signature, or a signer other than
     * the account that sends.
     */
    fun signed(send: PreparedSend, requestId: ByteArray, ur: UR): ByteArray {
        val answer = when (val parsed = EthSignature.decode(ur)) {
            is EthSignature.Result.Ok -> parsed.signature
            is EthSignature.Result.Rejected -> throw IllegalArgumentException("This code is not a signature from Lensly.")
        }
        require(answer.requestId.contentEquals(requestId)) { "This signature answers a different request." }
        val raw = send.tx.encodeSigned(answer.signature)
            ?: throw IllegalArgumentException("This signature is not one for an EIP-1559 transaction.")
        require(TxSigner.recoverSigner(send.payload, answer.signature) == send.from) {
            "This signature is from a different account."
        }
        return raw
    }

    /**
     * Broadcasts the signed transaction [raw] on [url] and returns its hash. The endpoint must report the hash of [raw], so the owner is
     * never sent to a transaction that Lumio did not sign.
     */
    fun broadcast(rpc: EvmRpc, url: String, raw: ByteArray): String {
        val expected = "0x" + Hash.sha3(raw).toHex()
        val reported = rpc.sendRawTransaction(url, raw)
        if (reported != expected) {
            throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint reported a different transaction hash. Check the explorer before you send again.")
        }
        return reported
    }

    /** The explorer page of [hash] on [chain], or null when no explorer is on file for it. */
    fun explorerLink(chain: EvmChain, hash: String): String? = chain.explorerUrl?.let { "$it/tx/$hash" }

    /** A transfer uses the endpoint's estimate. One that refuses to estimate uses the plain transfer gas, and the balance check still applies. */
    private fun gasFor(rpc: EvmRpc, url: String, from: String, to: String, valueWei: BigInteger): BigInteger = try {
        rpc.estimateGas(url, from, to, valueWei).max(TRANSFER)
    } catch (e: RpcFailure) {
        if (e.reason == RpcFailure.Reason.ERROR || e.reason == RpcFailure.Reason.MALFORMED) TRANSFER else throw e
    }
}

/** The owner's inputs for a send, checked. Each check throws IllegalArgumentException, with the words the form shows. */
object SendInput {
    private const val MAX_AMOUNT_CHARS = 40
    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    private val DECIMAL = Regex("\\d+(\\.\\d+)?")

    /**
     * The recipient as an EIP-55 address. Its capitals must match the checksum. An address in one case only has no checksum to check, so it
     * is refused, and the owner copies it with its capitals.
     */
    fun recipient(text: String): String {
        val address = text.trim()
        require(ADDRESS.matches(address)) { "Enter the recipient: 0x and 40 hex characters." }
        if (Keys.toChecksumAddress(address) == address) return address
        val hex = address.substring(2)
        val mixedCase = hex != hex.lowercase() && hex != hex.uppercase()
        throw IllegalArgumentException(
            if (mixedCase) {
                "The checksum does not match. Check the recipient address."
            } else {
                "This address has no checksum. Enter it with its EIP-55 capital letters."
            },
        )
    }

    /** The amount of [text] in wei, for a chain whose native coin has [decimals] decimal places. It must be more than zero. */
    fun amountWei(text: String, decimals: Int, symbol: String): BigInteger {
        val amount = text.trim()
        require(amount.length <= MAX_AMOUNT_CHARS && DECIMAL.matches(amount)) { "Enter an amount in $symbol, such as 0.01." }
        val value = BigDecimal(amount)
        require(value.stripTrailingZeros().scale() <= decimals) { "Use at most $decimals decimal places for $symbol." }
        val wei = value.movePointRight(decimals).toBigIntegerExact()
        require(wei.signum() > 0) { "The amount must be more than zero." }
        return wei
    }
}
