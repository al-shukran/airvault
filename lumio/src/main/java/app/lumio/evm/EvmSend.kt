package app.lumio.evm

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.TxType
import app.airvault.chains.evm.signer.UnsignedTx
import app.airvault.chains.evm.wallet.EvmWallet
import app.lensly.core.evm.TxSigner
import app.lensly.core.ur.EthSignature
import app.lensly.core.wallet.toHex
import app.lumio.DebugEvmFunds
import app.lumio.data.sched.Priority
import app.lumio.tokens.Erc20
import app.lumio.tokens.Token
import app.lumio.tokens.TokenBalances
import com.sparrowwallet.hummingbird.UR
import java.math.BigDecimal
import java.math.BigInteger
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/**
 * A send as far as Lumio can build it without a key: the inputs the owner gave, checked, and the unsigned EIP-1559 transaction they make.
 * The wallet signs [payload]. Lumio holds no key, so it can only show the request and check the answer.
 *
 * With no [token] the send moves the native coin: [valueWei] goes to [to]. With a [token] it moves that ERC-20 token: the transaction calls the
 * token contract with transfer([to], [valueWei]) and no value, so [valueWei] is in the token's base units.
 */
class PreparedSend(
    val chain: EvmChain,
    /** The account number of the sending account: the account path is m/44'/60'/[index]'/0/0. */
    val index: Int,
    /** The EIP-55 address that sends. */
    val from: String,
    /** The EIP-55 address that receives. */
    val to: String,
    /** The amount in the base units of [token], or in wei for the native coin. */
    val valueWei: BigInteger,
    /** The nonce: the pending transaction count of [from]. */
    val nonce: Long,
    /** The gas limit. */
    val gasLimit: BigInteger,
    /** The fee cap per gas, in wei. */
    val maxFeePerGas: BigInteger,
    /** The priority fee per gas, in wei. */
    val maxPriorityFeePerGas: BigInteger,
    /** The native balance of [from] at the read, in wei. It pays the gas, and a native send's amount too. */
    val balanceWei: BigInteger,
    /** The ERC-20 token that is sent, or null for the native coin. */
    val token: Token? = null,
    /** The balance of [token] that [from] holds at the read, in its base units. Null for the native coin. */
    val tokenBalance: BigInteger? = null,
) {
    private val call = callOf(to, valueWei, token)

    /** The unsigned transaction. */
    val tx: UnsignedTx = UnsignedTx(
        type = TxType.EIP1559,
        chainId = BigInteger.valueOf(chain.chainId),
        nonce = BigInteger.valueOf(nonce),
        to = call.target,
        value = call.value,
        data = call.data,
        gasLimit = gasLimit,
        gasPrice = null,
        maxPriorityFeePerGas = maxPriorityFeePerGas,
        maxFeePerGas = maxFeePerGas,
    )

    /** The payload that the wallet signs: [tx] in the form the Lensly parser reads back. */
    val payload: ByteArray = checkNotNull(tx.encode()) { "The transaction is not in canonical form." }

    /** The most the gas can cost, at the fee cap, in wei. */
    val maxFeeWei: BigInteger get() = gasLimit * maxFeePerGas

    /**
     * The most the send can cost in the native coin, in wei: the gas at the fee cap, and for a native send the amount too. A token send spends
     * its token amount on the token, so only its gas is native.
     */
    val maxCostWei: BigInteger get() = (if (token == null) valueWei else BigInteger.ZERO) + maxFeeWei

    /** This send with the nonce, the gas limit and the fees replaced, as the owner edits them. The amount and the parties stay. */
    fun withFees(nonce: Long, gasLimit: BigInteger, maxFeePerGas: BigInteger, maxPriorityFeePerGas: BigInteger): PreparedSend = PreparedSend(
        chain, index, from, to, valueWei, nonce, gasLimit, maxFeePerGas, maxPriorityFeePerGas, balanceWei, token, tokenBalance,
    )
}

/** The call a send makes: the [target] address, the native [value] it carries, and the [data]. */
private class Call(val target: String, val value: BigInteger, val data: String)

/** The call of a send of [valueWei] to [to]: a native transfer, or the token contract's transfer(to, amount) when a [token] is sent. */
private fun callOf(to: String, valueWei: BigInteger, token: Token?): Call =
    if (token == null) {
        Call(to, valueWei, "0x")
    } else {
        Call(Keys.toChecksumAddress(token.contract), BigInteger.ZERO, Erc20.transfer(to, valueWei))
    }

/** A send from one address of a paired wallet, as the owner builds it, and the sign request and answer that go with it. */
object EvmSend {
    /** The gas of a plain transfer. It is the fallback when the endpoint refuses to estimate the gas. */
    const val TRANSFER_GAS = 21_000L

    /** The gas of an ERC-20 transfer. It is the fallback when the endpoint refuses to estimate it, and most tokens use less. */
    const val TOKEN_TRANSFER_GAS = 65_000L

    /** The origin that the sign request names, so Lensly's review shows which app asked. */
    const val ORIGIN = "Lumio"

    private val TRANSFER = BigInteger.valueOf(TRANSFER_GAS)
    private val TOKEN_TRANSFER = BigInteger.valueOf(TOKEN_TRANSFER_GAS)
    private val TWO = BigInteger.valueOf(2)

    /**
     * Reads what a send of [valueWei] from [from] needs on [chain], and builds it: the balances, the pending nonce, the priority fee, a fee cap of
     * twice the next base fee plus the priority fee, and the gas. A [token] send moves that token's [valueWei] in its base units, and its balance
     * is checked first, so a short token is named before the gas is read. The native balance must then cover the gas. Throws
     * IllegalArgumentException, or [RpcFailure], with the owner's words. It reads through the RPC scheduler, so the caller runs it off the main thread.
     */
    suspend fun prepare(
        reads: EvmReads,
        chain: EvmChain,
        from: String,
        index: Int,
        to: String,
        valueWei: BigInteger,
        token: Token? = null,
    ): PreparedSend {
        // Debug builds only: a funded balance for the check, so the send reaches its signature and its broadcast. Release builds read the network.
        val balance = DebugEvmFunds.balance() ?: reads.balance(chain, from, Priority.FOREGROUND)
        val tokenBalance = token?.let { TokenBalances.balance(reads, chain, from, it.contract) }
        if (token != null && tokenBalance != null && valueWei > tokenBalance) {
            throw IllegalArgumentException(
                "The token balance is ${EvmFormat.amount(tokenBalance, token.decimals, token.symbol)}, but this send is " +
                    EvmFormat.amount(valueWei, token.decimals, token.symbol) + ".",
            )
        }
        val nonce = reads.nonce(chain, from, pending = true, Priority.FOREGROUND)
        val fees = reads.fees(chain, Priority.FOREGROUND)
        val gas = gasFor(reads, chain, from, callOf(to, valueWei, token), if (token == null) TRANSFER else TOKEN_TRANSFER)
        val send = PreparedSend(
            chain, index, from, to, valueWei, nonce, gas, fees.baseFee * TWO + fees.priorityFee, fees.priorityFee, balance, token, tokenBalance,
        )
        require(send.maxCostWei <= balance) {
            val spent = if (token == null) "this send can cost" else "the gas for this send can cost"
            "The balance is ${EvmFormat.amount(balance, chain.decimals, chain.symbol)}, but $spent up to " +
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
        derivationPath = EvmWallet.pathOf(send.index),
        sourceFingerprint = fingerprint.toInt(),
        address = Numeric.hexStringToByteArray(send.from),
        origin = ORIGIN,
    )

    /** A signature that Lensly gave for another send: another request ID, or another account. Lumio never broadcasts it. */
    class SignatureForAnotherSend(message: String) : IllegalArgumentException(message)

    /**
     * The raw signed transaction that [ur] answers for [send]. Throws IllegalArgumentException, with the owner's words, when [ur] is not
     * a signature for this request: another type, another request ID, a signature that is not an EIP-1559 signature, or a signer other than
     * the account that sends. A signature for another request or another account throws [SignatureForAnotherSend].
     */
    fun signed(send: PreparedSend, requestId: ByteArray, ur: UR): ByteArray {
        val answer = when (val parsed = EthSignature.decode(ur)) {
            is EthSignature.Result.Ok -> parsed.signature
            is EthSignature.Result.Rejected -> throw IllegalArgumentException("This code is not a signature from Lensly.")
        }
        if (!answer.requestId.contentEquals(requestId)) throw SignatureForAnotherSend("This signature answers a different request.")
        val raw = send.tx.encodeSigned(answer.signature)
            ?: throw IllegalArgumentException("This signature is not one for an EIP-1559 transaction.")
        if (TxSigner.recoverSigner(send.payload, answer.signature) != send.from) {
            throw SignatureForAnotherSend("This signature is from a different account.")
        }
        return raw
    }

    /**
     * Broadcasts the signed transaction [raw] on [chain] and returns its hash. The endpoint must report the hash of [raw], so the owner is
     * never sent to a transaction that Lumio did not sign. The broadcast is routed by the scheduler: with an MEV-protected provider healthy, only
     * such a provider receives it.
     */
    suspend fun broadcast(reads: EvmReads, chain: EvmChain, raw: ByteArray): String {
        val expected = "0x" + Hash.sha3(raw).toHex()
        val reported = reads.broadcast(chain, raw)
        if (reported != expected) {
            throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint reported a different transaction hash. Check the explorer before you send again.")
        }
        return reported
    }

    /** The explorer page of [hash] on [chain], or null when no explorer is on file for it. */
    fun explorerLink(chain: EvmChain, hash: String): String? = chain.explorerUrl?.let { "$it/tx/$hash" }

    /**
     * A send uses the endpoint's estimate of its [call]. One that refuses to estimate uses [fallback], and the balance checks still apply. A token
     * send whose sender holds too little is refused by the estimate too, so the fallback is what the balance check then names.
     */
    private suspend fun gasFor(reads: EvmReads, chain: EvmChain, from: String, call: Call, fallback: BigInteger): BigInteger = try {
        reads.estimateGas(chain, from, call.target, call.value, call.data).max(TRANSFER)
    } catch (e: RpcFailure) {
        if (e.reason == RpcFailure.Reason.ERROR || e.reason == RpcFailure.Reason.MALFORMED) fallback else throw e
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
