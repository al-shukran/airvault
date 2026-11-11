package app.lensly.app.sign

import app.lensly.app.defaultAccountLabel
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
import app.lensly.core.registry.TokenEntry
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.EthSignatureEncoder
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.SIGN_MESSAGE_FUNCTION
import app.lensly.ui.sign.TxReview
import app.lensly.ui.sign.UNKNOWN_CHAIN_NAME
import app.lensly.ui.sign.UNKNOWN_DECIMALS_NOTE
import app.lensly.ui.sign.UNKNOWN_UNIT
import app.lensly.ui.sign.assumedAmountNote
import app.lensly.ui.sign.unknownFeeNote
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
 *
 * A chain or token outside the registry is not refused. Its review shows it as unknown, with raw base units and a
 * warning, and [TxReview.needsRegistryConfirmation] is set. A call that cannot be decoded is still refused.
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

    /**
     * Checks [request] against [wallet]. A Mnemonic wallet's request names its master fingerprint and an account path,
     * m/44'/60'/0'/0/i. A Single Key wallet has neither, so its request must name the wallet's address, and only that
     * address signs. The derivation path is not read for a Single Key.
     */
    fun prepare(request: EthSignRequest, wallet: WalletView): Prepared {
        // The size check comes before any decoding of the payload.
        if (request.signData.size > EthSignRequest.MAX_SIGN_DATA_BYTES) {
            return Prepared.Refused("The data to sign is larger than 64 KiB")
        }
        if (request.dataType == DataType.TYPED_DATA) return Prepared.Refused("Typed data not supported yet")

        val masterFingerprint = wallet.masterFingerprint
        val index: Int
        val address: String
        if (masterFingerprint == null) {
            val requestedAddress = request.address
                ?: return Prepared.Refused("The request does not say which address to sign with")
            index = 0
            address = wallet.addressAt(index)
            if (!sameAddress(requestedAddress, address)) return Prepared.Refused("This request is for a different address")
        } else {
            val sourceFingerprint = request.sourceFingerprint
                ?: return Prepared.Refused("The request does not say which wallet it is for")
            if (sourceFingerprint != masterFingerprint) {
                return Prepared.Refused("This request is for a different wallet")
            }

            val accountIndex = accountIndexOf(request.derivationPath)
            if (accountIndex == null || accountIndex >= wallet.accountCount) return Prepared.Refused("Unknown account")
            index = accountIndex
            address = wallet.addressAt(index)
            val requestedAddress = request.address
            if (requestedAddress != null && !sameAddress(requestedAddress, address)) {
                return Prepared.Refused("This request is for a different address")
            }
        }

        val chainId = request.chainId ?: return Prepared.Refused("The request does not name a network")
        // A chain outside the registry has no network. It is reviewed as an unknown chain, with a warning, not refused.
        val label = (wallet.accountNames[index] ?: defaultAccountLabel(index)).uppercase()
        val target = Target(request, chainId, registry.network(chainId), index, address, label)

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
            networkName = network?.name ?: UNKNOWN_CHAIN_NAME,
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
            feeNote = feeNote(tx, network),
            warnings = emptyList(),
            nonce = tx.nonce.toLong(),
            txType = if (tx.type == TxType.EIP1559) "EIP-1559" else "Legacy",
            network = network,
        )

        val calldata = Numeric.hexStringToByteArray(tx.data)
        val review = if (calldata.isEmpty()) {
            base.copy(
                toAddress = to,
                amount = nativeAmount(tx.value, network),
                symbol = nativeUnit(network),
                amountNote = if (network == null) assumedAmountNote(tx.value) else null,
                function = "Native transfer",
            )
        } else {
            // An undecodable call is refused for any contract, so it is decoded before the token lookup.
            val call = Erc20Call.decode(calldata) ?: return Prepared.Refused("Unknown contract call")
            if (tx.value.signum() != 0) return Prepared.Refused("Token calls must not send native currency")
            // A contract outside the registry is an unknown token. Its decimals are not known, so its amounts stay raw.
            val token = registry.token(target.chainId, to)
            val amountNote = if (token == null) UNKNOWN_DECIMALS_NOTE else null
            when (call) {
                is Erc20Call.Transfer -> base.copy(
                    token = token,
                    toAddress = checksum(call.to),
                    amount = tokenAmount(call.amount, token),
                    symbol = token?.symbol ?: UNKNOWN_UNIT,
                    amountNote = amountNote,
                    tokenContract = to,
                    function = "Token transfer",
                )
                is Erc20Call.Approve -> {
                    val allowance = tokenAmount(call.amount, token)
                    base.copy(
                        token = token,
                        toAddress = checksum(call.spender),
                        amount = allowance,
                        symbol = token?.symbol ?: UNKNOWN_UNIT,
                        amountNote = amountNote,
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
            networkName = target.network?.name ?: UNKNOWN_CHAIN_NAME,
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

    /**
     * The fee rows of the review. Values are exact: wei converted to native units, and gas prices in gwei. On an
     * unknown chain the fee stays in base units, because its coin is not known.
     */
    private fun feeLines(tx: UnsignedTx, network: NetworkEntry?): List<Pair<String, String>> {
        return when (tx.type) {
            TxType.LEGACY_EIP155 -> {
                val gasPrice = checkNotNull(tx.gasPrice)
                listOf(
                    "Gas limit" to tx.gasLimit.toString(),
                    "Gas price" to gwei(gasPrice),
                    "Max fee" to "${nativeAmount(tx.gasLimit * gasPrice, network)} ${nativeUnit(network)}",
                )
            }
            TxType.EIP1559 -> {
                val maxFee = checkNotNull(tx.maxFeePerGas)
                listOf(
                    "Gas limit" to tx.gasLimit.toString(),
                    "Max fee per gas" to gwei(maxFee),
                    "Max priority fee" to gwei(checkNotNull(tx.maxPriorityFeePerGas)),
                    "Max fee" to "${nativeAmount(tx.gasLimit * maxFee, network)} ${nativeUnit(network)}",
                )
            }
        }
    }

    /** The note under the fee. A rollup gets the L2 caveat. An unknown chain gets the assumed value and a caveat. */
    private fun feeNote(tx: UnsignedTx, network: NetworkEntry?): String? = when {
        network == null -> unknownFeeNote(maxFeeWei(tx))
        network.feeModel == FeeModel.L2_ROLLUP -> L2_FEE_NOTE
        else -> null
    }

    /** The message as UTF-8 text when every character is printable, otherwise as 0x-prefixed hex. */
    private fun messageText(bytes: ByteArray): String {
        val text = utf8OrNull(bytes)
        return if (text != null && text.all(::isPrintable)) text else "0x" + bytes.toHex()
    }

    /**
     * A request that passed the wallet, account and address checks. [network] is null when the chain is not in the
     * registry.
     */
    private class Target(
        val request: EthSignRequest,
        val chainId: Long,
        val network: NetworkEntry?,
        val index: Int,
        val address: String,
        /** The FROM label of the review: the account's Display Name in capitals, or "ACCOUNT N" while it has none. */
        val label: String,
    )

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

        /** The most the sender can pay for execution: gas limit times gas price, or times the max fee per gas. */
        fun maxFeeWei(tx: UnsignedTx): BigInteger = tx.gasLimit * checkNotNull(tx.gasPrice ?: tx.maxFeePerGas)

        /** A native amount in the network's coin. An unknown network has no known coin, so it stays in base units. */
        fun nativeAmount(wei: BigInteger, network: NetworkEntry?): String =
            if (network == null) wei.toString() else Amounts.format(wei, network.nativeDecimals)

        /** The unit of a native amount: the coin's symbol, or [UNKNOWN_UNIT] for an unknown network. */
        fun nativeUnit(network: NetworkEntry?): String = network?.nativeSymbol ?: UNKNOWN_UNIT

        /** A token amount in the token's decimals. An unknown token has no known decimals, so its amount stays raw. */
        fun tokenAmount(raw: BigInteger, token: TokenEntry?): String =
            if (token == null) raw.toString() else Amounts.format(raw, token.decimals)

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
