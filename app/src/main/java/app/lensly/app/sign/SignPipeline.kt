package app.lensly.app.sign

import app.lensly.app.export.partsOf
import app.lensly.core.evm.MessageSigner
import app.lensly.core.evm.TxSigner
import app.lensly.core.evm.TxType
import app.lensly.core.evm.UnsignedTx
import app.lensly.core.registry.Amounts
import app.lensly.core.registry.Erc20Call
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.EthSignatureEncoder
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.SIGN_MESSAGE_FUNCTION
import app.lensly.ui.sign.TxReview
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** The outcome of [SignPipeline.prepare]: a review for the user to confirm, or a refusal that says why. */
sealed interface Prepared {
    /** [accountIndex] is i in m/44'/60'/0'/0/i. Nothing is signed until [SignPipeline.sign] runs. */
    data class Ready(val review: TxReview, val request: EthSignRequest, val accountIndex: Int) : Prepared

    data class Refused(val reason: String) : Prepared
}

/**
 * Checks a scanned eth-sign-request against the wallet and the registry, builds the review the user confirms, and
 * signs only after that confirmation. A failed check returns [Prepared.Refused] with a plain reason and nothing is
 * signed.
 */
class SignPipeline(private val registry: Registry) {

    fun prepare(urCbor: ByteArray, wallet: WalletView): Prepared =
        when (val decoded = EthSignRequest.decode(urCbor)) {
            is EthSignRequest.Result.Ok -> prepare(decoded.request, wallet)
            is EthSignRequest.Result.Rejected -> Prepared.Refused(decoded.reason)
        }

    /** As [prepare] for a scanned UR. A UR of any type other than eth-sign-request is refused. */
    fun prepare(ur: UR, wallet: WalletView): Prepared =
        when (val decoded = EthSignRequest.decode(ur)) {
            is EthSignRequest.Result.Ok -> prepare(decoded.request, wallet)
            is EthSignRequest.Result.Rejected -> Prepared.Refused(decoded.reason)
        }

    fun prepare(request: EthSignRequest, wallet: WalletView): Prepared {
        // The size check comes before any decoding of the payload.
        if (request.signData.size > EthSignRequest.MAX_SIGN_DATA_BYTES) {
            return Prepared.Refused("The data to sign is larger than 64 KiB")
        }
        if (request.dataType == DataType.TYPED_DATA) return Prepared.Refused("Typed data not supported yet")

        val sourceFingerprint = request.sourceFingerprint
            ?: return Prepared.Refused("The request does not say which wallet it is for")
        if (sourceFingerprint != wallet.masterFingerprint) {
            return Prepared.Refused("This request is for a different wallet")
        }

        val index = accountIndexOf(request.derivationPath)
        if (index == null || index >= wallet.accountCount) return Prepared.Refused("Unknown account")
        val address = wallet.addressAt(index)
        val requestedAddress = request.address
        if (requestedAddress != null && !sameAddress(requestedAddress, address)) {
            return Prepared.Refused("This request is for a different address")
        }

        val chainId = request.chainId ?: return Prepared.Refused("The request does not name a network")
        val network = registry.network(chainId) ?: return Prepared.Refused("Unknown network $chainId")
        val target = Target(request, chainId, network, index, address)

        return if (request.dataType == DataType.PERSONAL_MESSAGE) message(target) else transaction(target)
    }

    /**
     * Signs the reviewed [ready] with [privateKey] and returns the ur:eth-signature parts that answer its request.
     * Takes ownership of [privateKey] and zeroes it before returning, on success and on refusal alike. Throws
     * [IllegalArgumentException] when the request has no requestId to echo or the signer refuses the data.
     */
    fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> {
        try {
            val request = ready.request
            val requestId = requireNotNull(request.requestId) { "The request has no requestId to echo" }
            val signature = when (request.dataType) {
                DataType.TRANSACTION, DataType.TYPED_TRANSACTION ->
                    when (val result = TxSigner.sign(request.signData, request.dataType, privateKey)) {
                        is TxSigner.Result.Signed -> result.signature
                        is TxSigner.Result.Refused -> throw IllegalArgumentException(result.reason)
                    }
                DataType.PERSONAL_MESSAGE ->
                    when (val result = MessageSigner.sign(request.dataType, request.signData, privateKey)) {
                        is MessageSigner.Result.Signed -> result.signature
                        is MessageSigner.Result.Refused -> throw IllegalArgumentException(result.reason)
                    }
                DataType.TYPED_DATA -> throw IllegalArgumentException("Typed data not supported yet")
            }
            return partsOf(EthSignatureEncoder.encode(requestId, signature))
        } finally {
            privateKey.fill(0)
        }
    }

    private fun transaction(target: Target): Prepared {
        val request = target.request
        val tx = when (val decoded = UnsignedTx.decode(request.signData, request.dataType)) {
            is UnsignedTx.Result.Ok -> decoded.tx
            is UnsignedTx.Result.Rejected -> return Prepared.Refused(decoded.reason)
        }
        if (tx.chainId != BigInteger.valueOf(target.chainId)) {
            return Prepared.Refused(
                "The transaction is for chain ${tx.chainId}, but the request is for chain ${target.chainId}",
            )
        }
        if (tx.nonce.bitLength() >= Long.SIZE_BITS) {
            return Prepared.Refused("The transaction nonce is too large to show")
        }
        val to = tx.to ?: return Prepared.Refused("Contract creation is not supported")

        val network = target.network
        // Fields that depend on what the transaction does are set below, in each branch.
        val base = TxReview(
            networkName = network.name,
            chainId = target.chainId,
            fromAccountLabel = target.label,
            fromAddress = target.address,
            toAddress = "",
            amount = "",
            symbol = "",
            tokenContract = null,
            function = "",
            params = emptyList(),
            fee = feeLines(tx, network),
            feeNote = if (network.feeModel == FeeModel.L2_ROLLUP) L2_FEE_NOTE else null,
            warnings = emptyList(),
            nonce = tx.nonce.toLong(),
            txType = if (tx.type == TxType.EIP1559) "EIP-1559" else "Legacy",
            network = network,
        )

        val calldata = Numeric.hexStringToByteArray(tx.data)
        val review = if (calldata.isEmpty()) {
            base.copy(
                toAddress = to,
                amount = Amounts.format(tx.value, network.nativeDecimals),
                symbol = network.nativeSymbol,
                function = "Native transfer",
            )
        } else {
            val token = registry.token(target.chainId, to) ?: return Prepared.Refused("Unregistered token or contract")
            val call = Erc20Call.decode(calldata) ?: return Prepared.Refused("Unknown contract call")
            if (tx.value.signum() != 0) return Prepared.Refused("Token calls must not send native currency")
            when (call) {
                is Erc20Call.Transfer -> base.copy(
                    token = token,
                    toAddress = checksum(call.to),
                    amount = Amounts.format(call.amount, token.decimals),
                    symbol = token.symbol,
                    tokenContract = to,
                    function = "Token transfer",
                )
                is Erc20Call.Approve -> {
                    val allowance = Amounts.format(call.amount, token.decimals)
                    base.copy(
                        token = token,
                        toAddress = checksum(call.spender),
                        amount = allowance,
                        symbol = token.symbol,
                        tokenContract = to,
                        function = "Token approval",
                        params = listOf("spender" to checksum(call.spender), "amount" to allowance),
                        warnings = if (call.unlimited) listOf("UNLIMITED APPROVAL") else emptyList(),
                    )
                }
            }
        }
        return Prepared.Ready(review, request, target.index)
    }

    private fun message(target: Target): Prepared.Ready {
        val review = TxReview(
            networkName = target.network.name,
            chainId = target.chainId,
            fromAccountLabel = target.label,
            fromAddress = target.address,
            toAddress = "",
            amount = "",
            symbol = "",
            tokenContract = null,
            function = SIGN_MESSAGE_FUNCTION,
            params = listOf("message" to messageText(target.request.signData)),
            fee = emptyList(),
            feeNote = null,
            warnings = emptyList(),
            nonce = 0L,
            txType = "Personal message",
            network = target.network,
        )
        return Prepared.Ready(review, target.request, target.index)
    }

    /** The fee rows of the review. Values are exact: wei converted to native units, and gas prices in gwei. */
    private fun feeLines(tx: UnsignedTx, network: NetworkEntry): List<Pair<String, String>> {
        val symbol = network.nativeSymbol
        val decimals = network.nativeDecimals
        return when (tx.type) {
            TxType.LEGACY_EIP155 -> {
                val gasPrice = checkNotNull(tx.gasPrice)
                listOf(
                    "Gas limit" to tx.gasLimit.toString(),
                    "Gas price" to gwei(gasPrice),
                    "Max fee" to "${Amounts.format(tx.gasLimit * gasPrice, decimals)} $symbol",
                )
            }
            TxType.EIP1559 -> {
                val maxFee = checkNotNull(tx.maxFeePerGas)
                listOf(
                    "Gas limit" to tx.gasLimit.toString(),
                    "Max fee per gas" to gwei(maxFee),
                    "Max priority fee" to gwei(checkNotNull(tx.maxPriorityFeePerGas)),
                    "Max fee" to "${Amounts.format(tx.gasLimit * maxFee, decimals)} $symbol",
                )
            }
        }
    }

    /** The message as UTF-8 text when every character is printable, otherwise as 0x-prefixed hex. */
    private fun messageText(bytes: ByteArray): String {
        val text = utf8OrNull(bytes)
        return if (text != null && text.all(::isPrintable)) text else "0x" + bytes.toHex()
    }

    /** A request that passed the wallet, account, address and network checks. */
    private class Target(
        val request: EthSignRequest,
        val chainId: Long,
        val network: NetworkEntry,
        val index: Int,
        val address: String,
    ) {
        val label: String get() = "ACCOUNT ${index + 1}"
    }

    private companion object {
        const val L2_FEE_NOTE = "L1 data fee is not computable offline; total may be higher"
        const val GWEI_DECIMALS = 9
        const val ACCOUNT_PATH_LENGTH = 5

        /** The first four steps of every Lensly account path, m/44'/60'/0'/0. The last step is the index. */
        val ACCOUNT_PATH_PREFIX = listOf(
            PathStep(44, true),
            PathStep(60, true),
            PathStep(0, true),
            PathStep(0, false),
        )

        fun gwei(wei: BigInteger): String = "${Amounts.format(wei, GWEI_DECIMALS)} gwei"

        /** i when [path] is exactly m/44'/60'/0'/0/i, otherwise null. */
        fun accountIndexOf(path: List<PathStep>): Int? {
            if (path.size != ACCOUNT_PATH_LENGTH) return null
            if (path.subList(0, ACCOUNT_PATH_PREFIX.size) != ACCOUNT_PATH_PREFIX) return null
            val last = path[ACCOUNT_PATH_PREFIX.size]
            return if (last.hardened) null else last.index
        }

        fun sameAddress(address: ByteArray, expected: String): Boolean =
            ("0x" + address.toHex()).equals(expected, ignoreCase = true)

        fun checksum(address: String): String = Keys.toChecksumAddress(address)

        fun utf8OrNull(bytes: ByteArray): String? = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

        fun isPrintable(char: Char): Boolean =
            char == '\n' || char == '\r' || char == '\t' || !Character.isISOControl(char)
    }
}
