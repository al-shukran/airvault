package app.lensly.app.sign

import app.lensly.app.autoAccountName
import app.lensly.app.export.partsOf
import app.lensly.core.evm.MessageSigner
import app.lensly.core.evm.TxSigner
import app.lensly.core.evm.TxType
import app.lensly.core.evm.TypedData
import app.lensly.core.evm.TypedDataParser
import app.lensly.core.evm.TypedDataSigner
import app.lensly.core.evm.TypedValue
import app.lensly.core.evm.UnsignedTx
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.InnerCall
import app.lensly.core.parse.Multicall
import app.lensly.core.parse.MulticallShape
import app.lensly.core.parse.ParsedRequest
import app.lensly.core.parse.ParserLimits
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.callCount
import app.lensly.core.parse.deepestLevel
import app.lensly.core.parse.encodesAs
import app.lensly.core.registry.Amounts
import app.lensly.core.registry.ContractCall
import app.lensly.core.registry.DecodedParam
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.Registry
import app.lensly.core.registry.StaticAbiDecoder
import app.lensly.core.registry.StaticDecode
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.EthSignatureEncoder
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.ALL_NFTS_WARNING
import app.lensly.ui.sign.NestedCall
import app.lensly.ui.sign.SIGN_MESSAGE_FUNCTION
import app.lensly.ui.sign.TxReview
import app.lensly.ui.sign.TypedReview
import app.lensly.ui.sign.UNKNOWN_CHAIN_NAME
import app.lensly.ui.sign.UNKNOWN_DECIMALS_NOTE
import app.lensly.ui.sign.UNKNOWN_UNIT
import app.lensly.ui.sign.assumedAmountNote
import app.lensly.ui.sign.groupHex
import app.lensly.ui.sign.unknownFeeNote
import app.lensly.ui.sign.unlimitedApprovalWarning
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** The outcome of [SignPipeline.prepare]: a review for the user to confirm, or a refusal that says why. */
sealed interface Prepared {
    /**
     * [accountIndex] is i in m/44'/60'/0'/0/i, and [walletId] is the wallet that holds that account. Nothing is signed until
     * [SignPipeline.sign] runs.
     */
    data class Ready(
        val review: TxReview,
        val request: EthSignRequest,
        val accountIndex: Int,
        val walletId: String,
        /** The transaction the Parser Sandbox decoded from the request's data. Null for a request that is not a transaction. */
        val tx: UnsignedTx? = null,
        /** The typed message the Parser Sandbox parsed, which is what gets signed. Null for a request that is not typed data. */
        val typed: TypedData? = null,
    ) : Prepared

    /** [call] is set when the refusal is for a contract call Lensly cannot decode, so the refusal can show it. */
    data class Refused(val reason: String, val call: UndecodedCall? = null) : Prepared
}

/**
 * A contract call Lensly cannot decode, as its refusal shows it: the contract, the network, the native value, the selector,
 * and the calldata after the selector in 32-byte words. Hex has no 0x prefix. The last word is shorter when the calldata does
 * not end on a word boundary. [proxy] is the implementation a whitelisted proxy is bound to, and null for any other call.
 */
data class UndecodedCall(
    val target: String,
    val networkName: String,
    val chainId: Long,
    val value: String,
    val selector: String,
    val words: List<String>,
    val proxy: ProxyBinding? = null,
)

/**
 * A call Lensly cannot decode that the signed whitelist approves. [signature] and [meaning] are the approved facts, and [call]
 * is the whole call. The review shows all of it before the owner confirms.
 */
data class WhitelistedCall(
    val signature: String,
    val meaning: String,
    val call: UndecodedCall,
)

/**
 * Checks a scanned eth-sign-request, as the Parser Sandbox parsed it, against the wallet and the registry, builds the review the user confirms, and
 * signs only after that confirmation. A failed check returns [Prepared.Refused] with a plain reason and nothing is
 * signed.
 *
 * A chain or token outside the registry is not refused. Its review shows it as unknown, with raw base units and a
 * warning, and [TxReview.needsRegistryConfirmation] is set. A call that cannot be decoded is refused unless the signed
 * whitelist approves its contract and selector. [proxyPolicy] decides what a whitelisted proxy gets.
 *
 * The pipeline parses nothing itself. The request arrives parsed, and [parser] decodes the calldata of a whitelisted call.
 */
class SignPipeline(
    private val registry: Registry,
    private val parser: RequestParser,
    private val proxyPolicy: ProxyPolicy = ProxyPolicy.DEFAULT,
) {

    /**
     * Checks the request of [parsed] against [wallet]. A Mnemonic wallet's request names its master fingerprint and an account path,
     * m/44'/60'/0'/0/i. A Single Key wallet has neither, so its request must name the wallet's address, and only that
     * address signs. The derivation path is not read for a Single Key.
     */
    fun prepare(parsed: ParsedRequest, wallet: WalletView): Prepared {
        val request = parsed.request
        // The size check comes before any decoding of the payload.
        if (request.signData.size > EthSignRequest.MAX_SIGN_DATA_BYTES) {
            return Prepared.Refused("The data to sign is larger than 64 KiB")
        }
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
        val label = (wallet.accountNames[index] ?: autoAccountName(wallet.autoNameBase, index)).uppercase()
        val target = Target(parsed, chainId, registry.network(chainId), wallet, index, address, label)

        return when (request.dataType) {
            DataType.PERSONAL_MESSAGE -> message(target)
            DataType.TYPED_DATA -> typedData(target)
            else -> transaction(target)
        }
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
                    TxSigner.sign(request.signData, requireNotNull(ready.tx) { CANNOT_BE_READ }, privateKey)
                DataType.PERSONAL_MESSAGE ->
                    when (val result = MessageSigner.sign(request.dataType, request.signData, privateKey)) {
                        is MessageSigner.Result.Signed -> result.signature
                        is MessageSigner.Result.Refused -> throw IllegalArgumentException(result.reason)
                    }
                DataType.TYPED_DATA -> {
                    val typed = requireNotNull(ready.typed) { CANNOT_BE_READ }
                    val chainId = requireNotNull(request.chainId) { "The request does not name a network" }
                    when (val result = TypedDataSigner.sign(request.dataType, typed, chainId, privateKey)) {
                        is TypedDataSigner.Result.Signed -> result.signature
                        is TypedDataSigner.Result.Refused -> throw IllegalArgumentException(result.reason)
                    }
                }
            }
            return partsOf(EthSignatureEncoder.encode(requestId, signature))
        } finally {
            privateKey.fill(0)
        }
    }

    private fun transaction(target: Target): Prepared {
        val tx = when (val decoded = target.parsed.tx) {
            is UnsignedTx.Result.Ok -> decoded.tx
            is UnsignedTx.Result.Rejected -> return Prepared.Refused(decoded.reason)
            null -> return Prepared.Refused(CANNOT_BE_READ)
        }
        // The review shows the fields of tx, so they must rebuild the bytes that get signed. Otherwise it shows another transaction.
        if (tx.encode()?.contentEquals(target.request.signData) != true) return Prepared.Refused(CANNOT_BE_READ)
        if (tx.chainId != BigInteger.valueOf(target.chainId)) {
            return Prepared.Refused(
                "The transaction is for chain ${tx.chainId}, but the request is for chain ${target.chainId}",
            )
        }
        if (tx.nonce.bitLength() >= Long.SIZE_BITS) {
            return Prepared.Refused("The transaction nonce is too large to show")
        }
        val to = tx.to ?: return Prepared.Refused("Contract creation is not supported")

        val calldata = Numeric.hexStringToByteArray(tx.data)
        val review = if (calldata.isEmpty()) {
            nativeReview(target, to, tx.value)
        } else {
            // A multicall lists each of its calls, and each call is decided as a call of its own.
            target.parsed.multicall?.let { return multicallReview(target, tx, to, calldata, it) }
            // An undecodable call is a Decode Gap, so it is decided by the whitelist before the token lookup.
            val call = target.parsed.call ?: return decodeGap(target, tx, to, calldata)
            // The labels are shown only when the decoded call is exactly the calldata that gets signed.
            if (call.encode()?.contentEquals(calldata) != true) return Prepared.Refused(CANNOT_BE_READ)
            if (tx.value.signum() != 0) return Prepared.Refused("Token calls must not send native currency")
            // A contract outside the registry is an unknown token. Its decimals are not known, so its amounts stay raw.
            callReview(target, call, to, registry.token(target.chainId, to))
        }
        return ready(target, review, tx)
    }

    /**
     * A Decode Gap: calldata Lensly cannot decode. It is refused unless its contract and Function Selector are on the signed
     * whitelist, as [whitelistedGap] decides. The review of an approved call carries its approved meaning and the proxy binding.
     */
    private fun decodeGap(target: Target, tx: UnsignedTx, contract: String, calldata: ByteArray): Prepared =
        when (val gap = whitelistedGap(target, contract, calldata, tx.value)) {
            is GapOutcome.Refused -> Prepared.Refused(gap.reason, gap.call)
            is GapOutcome.Approved -> ready(
                target,
                target.review(function = gap.function, toAddress = checksum(contract), params = gap.params)
                    .copy(whitelisted = gap.whitelisted),
                tx,
            )
        }

    /** What the whitelist decides for an undecodable call: approved, with the facts its review shows, or refused with its reason. */
    private sealed interface GapOutcome {
        class Approved(val function: String, val params: List<Pair<String, String>>, val whitelisted: WhitelistedCall) : GapOutcome

        class Refused(val reason: String, val call: UndecodedCall) : GapOutcome
    }

    /**
     * The whitelist's decision for an undecodable call to [contract] that sends [value]. A call the whitelist does not approve is
     * refused. A whitelisted proxy is refused under Bind to implementation and under Refuse all proxies. Under Warn and confirm it is
     * approved with its approved meaning, and the approval carries the proxy binding. The sandbox decodes the arguments, and they are
     * checked against the calldata before they are shown.
     */
    private fun whitelistedGap(target: Target, contract: String, calldata: ByteArray, value: BigInteger): GapOutcome {
        val entry = whitelistEntry(target, contract, calldata)
        val proxy = entry?.takeIf { it.upgradeable }?.proxy
        val call = undecodedCall(target, contract, value, calldata, proxy = proxy)
        if (entry == null) return GapOutcome.Refused(UNKNOWN_CALL, call)
        if (entry.upgradeable && proxyPolicy == ProxyPolicy.BIND_TO_IMPLEMENTATION) {
            return GapOutcome.Refused(BOUND_PROXY_REFUSAL, call)
        }
        if (entry.upgradeable && proxyPolicy == ProxyPolicy.REFUSE_ALL_PROXIES) {
            return GapOutcome.Refused(ANY_PROXY_REFUSAL, call)
        }
        val decoded = when (val result = parser.decodeStatic(calldata, entry.params)) {
            is StaticDecode.Ok -> result.params
            is StaticDecode.Refused -> return GapOutcome.Refused(result.reason, call)
        }
        // The sandbox must answer with exactly the params the entry declares, in their order.
        if (decoded.map { it.param } != entry.params) return GapOutcome.Refused(CANNOT_BE_READ, call)
        // The labels are shown only when the decoded values are exactly the arguments that get signed.
        if (StaticAbiDecoder.encode(decoded)?.contentEquals(argumentsOf(calldata)) != true) {
            return GapOutcome.Refused(CANNOT_BE_READ, call)
        }
        val token = registry.token(target.chainId, contract)
        return GapOutcome.Approved(
            function = entry.signature.substringBefore('('),
            params = decoded.map { paramValue(it, token) },
            whitelisted = WhitelistedCall(entry.signature, entry.meaning, call),
        )
    }

    /**
     * A multicall. Its calldata must be exactly what the calls the sandbox read encode to, and each call is then decided where it
     * sits. The review lists every call in order, indented by its level, under the batch's own function and deadline.
     */
    private fun multicallReview(target: Target, tx: UnsignedTx, to: String, calldata: ByteArray, multicall: Multicall): Prepared {
        val shape = encodesAs(calldata, multicall) ?: return Prepared.Refused(CANNOT_BE_READ)
        if (multicall.deepestLevel() > ParserLimits.MAX_NESTED_DEPTH) return Prepared.Refused(TOO_DEEP_REFUSAL)
        if (multicall.callCount() > ParserLimits.MAX_NESTED_CALLS) return Prepared.Refused(TOO_MANY_CALLS_REFUSAL)
        val rows = ArrayList<NestedCall>()
        checkCalls(target, tx, to, shape, multicall, level = 1, rows)?.let { return it }
        val network = target.network
        val review = target.review(
            function = shape.function,
            signature = shape.signature,
            toAddress = checksum(to),
            amount = nativeAmount(tx.value, network),
            symbol = nativeUnit(network),
            amountNote = if (network == null) assumedAmountNote(tx.value) else null,
            params = deadlineParams(multicall),
            nested = rows,
        )
        return ready(target, review, tx)
    }

    /**
     * Decides each call of [multicall], whose calldata has [shape], and lists it in [rows] at [level]. A nested batch is checked against
     * its own calldata, and its calls one level down. Returns the refusal of the first call that fails, or null when every call passes.
     */
    private fun checkCalls(
        target: Target,
        tx: UnsignedTx,
        to: String,
        shape: MulticallShape,
        multicall: Multicall,
        level: Int,
        rows: MutableList<NestedCall>,
    ): Prepared.Refused? {
        for (inner in multicall.calls) {
            // A Multicall3 call names its own contract. Any other call goes to the contract its batch goes to.
            val contract = inner.target ?: to
            // Every call of the batch meets the denylist, as the request does: a BLOCK match refuses it, and a WARN match stays on its row.
            val denied = registry.denylistMatches(target.chainId, contract, inner.calldata)
            denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { return Prepared.Refused(it.reason) }
            val shown = inner.target?.let { checksum(it) }
            val flags = flagsOf(shape, inner, target.network)
            val nested = inner.nested
            val decoded = inner.call
            when {
                nested != null -> {
                    val nestedShape = encodesAs(inner.calldata, nested) ?: return Prepared.Refused(CANNOT_BE_READ)
                    rows += NestedCall(level, shown, nestedShape.function, flags + deadlineParams(nested), denylisted = denied)
                    checkCalls(target, tx, contract, nestedShape, nested, level + 1, rows)?.let { return it }
                }
                decoded != null -> {
                    // The labels are shown only when the decoded call is exactly the calldata that gets signed.
                    if (decoded.encode()?.contentEquals(inner.calldata) != true) return Prepared.Refused(CANNOT_BE_READ)
                    val review = callReview(target, decoded, contract, registry.token(target.chainId, contract))
                    rows += NestedCall(
                        depth = level,
                        target = shown,
                        function = review.function,
                        params = flags + review.params,
                        warnings = review.warnings,
                        note = review.amountNote,
                        unregistered = review.needsRegistryConfirmation,
                        denylisted = denied,
                    )
                }
                else -> when (val gap = whitelistedGap(target, contract, inner.calldata, inner.value ?: tx.value)) {
                    is GapOutcome.Refused -> return Prepared.Refused(gap.reason, gap.call)
                    is GapOutcome.Approved ->
                        rows += NestedCall(level, shown, gap.function, flags + gap.params, gap.whitelisted, denylisted = denied)
                }
            }
        }
        return null
    }

    /** The flags a Multicall3 call carries, shown ahead of its own arguments: whether it may fail, and the value it sends. */
    private fun flagsOf(shape: MulticallShape, inner: InnerCall, network: NetworkEntry?): List<Pair<String, String>> = buildList {
        if (shape.hasAllowFailure) add("allowFailure (bool)" to "${inner.allowFailure}")
        if (shape.hasValue) {
            inner.value?.let { add("value (uint256)" to "${nativeAmount(it, network)} ${nativeUnit(network)}") }
        }
    }

    /** The deadline of a multicall that carries one, as the review's parameter. */
    private fun deadlineParams(multicall: Multicall): List<Pair<String, String>> =
        listOfNotNull(multicall.deadline?.let { "deadline (uint256)" to it.toString() })

    /**
     * One decoded argument as the review lists it: its name and type, then its full value. A tokenRef uint named amount,
     * value or wad also shows its quantity in the registered token's decimals.
     */
    private fun paramValue(decoded: DecodedParam, token: TokenEntry?): Pair<String, String> {
        val param = decoded.param
        val value = if (param.tokenRef && token != null && param.type.startsWith("uint") && param.name in AMOUNT_PARAMS) {
            // The value came from the sandbox, so it is shown as it is when it is not a number.
            decoded.value.toBigIntegerOrNull()?.let { amountText(it, token) } ?: decoded.value
        } else {
            decoded.value
        }
        return "${param.name} (${param.type})" to value
    }

    /** The arguments of [calldata], which is what follows its selector. A calldata shorter than a selector has none. */
    private fun argumentsOf(calldata: ByteArray): ByteArray =
        calldata.copyOfRange(minOf(SELECTOR_BYTES, calldata.size), calldata.size)

    /** The whitelist entry for [contract] and the selector of [calldata] on the target's chain, or null when there is none. */
    private fun whitelistEntry(target: Target, contract: String, calldata: ByteArray): WhitelistEntry? {
        if (calldata.size < SELECTOR_BYTES) return null
        return registry.whitelist(target.chainId, contract, "0x" + calldata.copyOfRange(0, SELECTOR_BYTES).toHex())
    }

    /**
     * The review with the fee, nonce and transaction type of [tx] added, ready for the owner to confirm. Every transaction review
     * leaves here, so the denylist is checked here: a BLOCK match refuses, and a WARN match stays on the review with its reason and
     * needs the owner's check before the hold-to-sign gate.
     */
    private fun ready(target: Target, review: TxReview, tx: UnsignedTx): Prepared {
        val denied = tx.to?.let { registry.denylistMatches(target.chainId, it, Numeric.hexStringToByteArray(tx.data)) }.orEmpty()
        denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { return Prepared.Refused(it.reason) }
        val network = target.network
        return Prepared.Ready(
            review.copy(
                fee = feeLines(tx, network),
                feeNote = feeNote(tx, network),
                nonce = tx.nonce.toLong(),
                txType = when (tx.type) {
                    TxType.LEGACY_EIP155 -> "Legacy"
                    TxType.EIP2930 -> "EIP-2930"
                    TxType.EIP1559 -> "EIP-1559"
                },
                denylisted = denied,
            ),
            target.request,
            target.index,
            target.walletId,
            tx,
        )
    }

    /** The review of a native transfer of [value] to [to], in the network's coin. */
    private fun nativeReview(target: Target, to: String, value: BigInteger): TxReview {
        val network = target.network
        return target.review(
            function = "Native transfer",
            toAddress = to,
            amount = nativeAmount(value, network),
            symbol = nativeUnit(network),
            amountNote = if (network == null) assumedAmountNote(value) else null,
        )
    }

    /**
     * The review of a decoded contract call on [contract]. [token] is the registry's token at that address, or null. The review
     * names the ABI function and its signature, lists every argument, and carries the warnings the call earns.
     */
    private fun callReview(target: Target, call: ContractCall, contract: String, token: TokenEntry?): TxReview {
        // A call that moves or approves a fungible amount. Its amount is in the token's decimals when the token is known.
        fun fungible(
            function: String,
            recipient: String,
            value: BigInteger,
            params: List<Pair<String, String>>,
            warnings: List<String> = emptyList(),
        ): TxReview = target.review(
            function = function,
            signature = call.signature,
            toAddress = recipient,
            amount = tokenAmount(value, token),
            symbol = token?.symbol ?: UNKNOWN_UNIT,
            amountNote = if (token == null) UNKNOWN_DECIMALS_NOTE else null,
            tokenContract = contract,
            token = token,
            params = params,
            warnings = warnings,
        )

        return when (call) {
            is ContractCall.Transfer -> fungible(
                function = call.name,
                recipient = checksum(call.to),
                value = call.value,
                params = listOf("to" to checksum(call.to), "value" to amountText(call.value, token)),
            )
            is ContractCall.Approve -> fungible(
                function = call.name,
                recipient = checksum(call.spender),
                value = call.value,
                params = listOf("spender" to checksum(call.spender), "value" to amountText(call.value, token)),
                warnings = if (call.unlimited) listOf(unlimitedApprovalWarning(token?.symbol)) else emptyList(),
            )
            is ContractCall.TransferFrom ->
                if (token != null) {
                    fungible(
                        function = call.name,
                        recipient = checksum(call.to),
                        value = call.value,
                        params = listOf(
                            "from" to checksum(call.from),
                            "to" to checksum(call.to),
                            "value" to amountText(call.value, token),
                        ),
                    )
                } else {
                    // The contract is not known, so its third argument is a token amount or a token id. Neither is shown as an amount.
                    target.review(
                        function = "${call.name} (token or NFT)",
                        signature = call.signature,
                        toAddress = checksum(call.to),
                        tokenContract = contract,
                        params = listOf(
                            "from" to checksum(call.from),
                            "to" to checksum(call.to),
                            "amount or tokenId" to call.value.toString(),
                        ),
                    )
                }
            is ContractCall.IncreaseAllowance -> fungible(
                function = call.name,
                recipient = checksum(call.spender),
                value = call.addedValue,
                params = listOf(
                    "spender" to checksum(call.spender),
                    "addedValue" to amountText(call.addedValue, token),
                ),
                warnings = if (call.unlimited) listOf(unlimitedApprovalWarning(token?.symbol)) else emptyList(),
            )
            is ContractCall.DecreaseAllowance -> fungible(
                function = call.name,
                recipient = checksum(call.spender),
                value = call.subtractedValue,
                params = listOf(
                    "spender" to checksum(call.spender),
                    "subtractedValue" to amountText(call.subtractedValue, token),
                ),
            )
            is ContractCall.SetApprovalForAll -> target.review(
                function = call.name,
                signature = call.signature,
                toAddress = checksum(call.operator),
                tokenContract = contract,
                params = listOf("operator" to checksum(call.operator), "approved" to call.approved.toString()),
                warnings = if (call.approved) listOf(ALL_NFTS_WARNING) else emptyList(),
            )
            is ContractCall.SafeTransferFrom -> target.review(
                function = call.name,
                signature = call.signature,
                toAddress = checksum(call.to),
                amount = "#${call.tokenId}",
                tokenContract = contract,
                params = listOf(
                    "from" to checksum(call.from),
                    "to" to checksum(call.to),
                    "tokenId" to call.tokenId.toString(),
                ),
            )
        }
    }

    /**
     * What a refusal or a whitelisted review of an undecodable call shows: its contract, network, native value, selector and
     * calldata words, and the proxy binding when the contract is a whitelisted proxy.
     */
    private fun undecodedCall(
        target: Target,
        contract: String,
        value: BigInteger,
        calldata: ByteArray,
        proxy: ProxyBinding?,
    ): UndecodedCall =
        UndecodedCall(
            target = checksum(contract),
            networkName = target.network?.name ?: UNKNOWN_CHAIN_NAME,
            chainId = target.chainId,
            value = "${nativeAmount(value, target.network)} ${nativeUnit(target.network)}",
            selector = calldata.copyOfRange(0, minOf(SELECTOR_BYTES, calldata.size)).toHex(),
            words = (SELECTOR_BYTES until calldata.size step WORD_BYTES).map { start ->
                calldata.copyOfRange(start, minOf(start + WORD_BYTES, calldata.size)).toHex()
            },
            proxy = proxy,
        )

    private fun message(target: Target): Prepared.Ready {
        val bytes = target.request.signData
        val review = target.review(
            function = SIGN_MESSAGE_FUNCTION,
            txType = "Personal message",
            params = listOf("message" to messageText(bytes), "size" to sizeText(bytes)),
        )
        return Prepared.Ready(review, target.request, target.index, target.walletId)
    }

    /**
     * Typed data (EIP-712). The domain must name the request's chain, and that chain must be in the registry: the domain chain must match
     * a Network Entry, so a message for any other chain is refused before its review. A BLOCK denylist entry refuses the message with the
     * entry's reason. A WARN entry is shown on the review with its reason, and HOLD TO SIGN waits for the owner's confirmation of it.
     * Every field of the message is shown before the gate.
     */
    private fun typedData(target: Target): Prepared {
        val typed = when (val parsed = target.parsed.typed) {
            is TypedDataParser.Result.Ok -> parsed.data
            is TypedDataParser.Result.Rejected -> return Prepared.Refused(parsed.reason)
            null -> return Prepared.Refused(CANNOT_BE_READ)
        }
        val domainChain = typed.domainChainId ?: return Prepared.Refused(NO_DOMAIN_CHAIN)
        if (domainChain != BigInteger.valueOf(target.chainId)) {
            return Prepared.Refused("The typed data is for chain $domainChain, but the request names chain ${target.chainId}")
        }
        if (target.network == null) return Prepared.Refused(unknownTypedChain(target.chainId))
        val verifyingContract = (typed.domain.fields["verifyingContract"] as? TypedValue.BytesValue)?.let {
            checksum(it.bytes.toHex())
        }
        val denied = registry.typedDenylistMatches(target.chainId, verifyingContract, typed.primaryType)
        denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { return Prepared.Refused(it.reason) }

        val built = typedDataReview(typed, registry, target.chainId, target.network?.name ?: UNKNOWN_CHAIN_NAME)
        val review = target.review(
            function = typed.primaryType,
            txType = TYPED_DATA_TYPE,
            token = built.token,
            params = built.params,
            warnings = built.warnings,
            denylisted = denied,
            typed = built.review,
        )
        return Prepared.Ready(review, target.request, target.index, target.walletId, typed = typed)
    }

    /**
     * The review fields every request shares: the wallet, the account, the network and the origin. The caller sets what the
     * request does.
     */
    private fun Target.review(
        function: String,
        txType: String = "",
        signature: String? = null,
        toAddress: String = "",
        amount: String = "",
        symbol: String = "",
        tokenContract: String? = null,
        token: TokenEntry? = null,
        amountNote: String? = null,
        params: List<Pair<String, String>> = emptyList(),
        warnings: List<String> = emptyList(),
        nested: List<NestedCall> = emptyList(),
        denylisted: List<DenylistEntry> = emptyList(),
        typed: TypedReview? = null,
    ): TxReview = TxReview(
        networkName = network?.name ?: UNKNOWN_CHAIN_NAME,
        chainId = chainId,
        fromAccountLabel = label,
        fromAddress = address,
        toAddress = toAddress,
        amount = amount,
        symbol = symbol,
        tokenContract = tokenContract,
        function = function,
        params = params,
        fee = emptyList(),
        feeNote = null,
        warnings = warnings,
        nonce = 0L,
        txType = txType,
        network = network,
        token = token,
        amountNote = amountNote,
        signature = signature,
        walletName = wallet.name,
        walletIdentity = wallet.identity,
        accountIdentity = wallet.accountIdentity(index),
        origin = request.origin,
        nested = nested,
        denylisted = denylisted,
        typed = typed,
    )

    /**
     * The fee rows of the review. Values are exact: wei converted to native units, and gas prices in gwei. On an
     * unknown chain the fee stays in base units, because its coin is not known.
     */
    private fun feeLines(tx: UnsignedTx, network: NetworkEntry?): List<Pair<String, String>> {
        return when (tx.type) {
            TxType.LEGACY_EIP155, TxType.EIP2930 -> {
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

    /** The message as text when it reads as text, otherwise as 0x-prefixed hex, grouped. */
    private fun messageText(bytes: ByteArray): String = printableText(bytes) ?: "0x" + groupHex(bytes.toHex())

    /** The length of a message: its characters when it reads as text, and its bytes. */
    private fun sizeText(bytes: ByteArray): String {
        val text = printableText(bytes) ?: return "${bytes.size} bytes"
        return "${text.codePointCount(0, text.length)} characters, ${bytes.size} bytes"
    }

    /** The message as UTF-8 text, when every character is one a reader can see. Otherwise null. */
    private fun printableText(bytes: ByteArray): String? = utf8OrNull(bytes)?.takeIf { text -> text.all(::isPrintable) }

    /**
     * A request that passed the wallet, account and address checks. [network] is null when the chain is not in the
     * registry.
     */
    private class Target(
        val parsed: ParsedRequest,
        val chainId: Long,
        val network: NetworkEntry?,
        val wallet: WalletView,
        val index: Int,
        val address: String,
        /** The FROM label of the review: the account's Display Name in capitals, or its Auto-Name while it has none. */
        val label: String,
    ) {
        val request: EthSignRequest get() = parsed.request
        val walletId: String get() = wallet.id
    }

    private companion object {
        const val L2_FEE_NOTE = "L1 data fee is not computable offline; total may be higher"
        const val TYPED_DATA_TYPE = "Typed data (EIP-712)"
        const val NO_DOMAIN_CHAIN = "The typed data names no chain ID in its domain"

        /** The refusal of typed data for a chain the registry does not hold. */
        fun unknownTypedChain(chainId: Long): String =
            "The typed data is for chain $chainId, which is not in Lensly's network registry, so it is refused."
        const val UNKNOWN_CALL = "Unknown contract call"
        const val BOUND_PROXY_REFUSAL = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is " +
            "still the approved version, so your Proxy Policy refuses it."
        const val ANY_PROXY_REFUSAL = "This contract is an upgradeable proxy, and your Proxy Policy refuses every proxy."
        val TOO_DEEP_REFUSAL = "The calls nest deeper than ${ParserLimits.MAX_NESTED_DEPTH} levels, so Lensly cannot show every call."
        val TOO_MANY_CALLS_REFUSAL = "The transaction holds more than ${ParserLimits.MAX_NESTED_CALLS} calls, " +
            "so Lensly cannot show every call."
        const val GWEI_DECIMALS = 9
        const val ACCOUNT_PATH_LENGTH = 5
        const val SELECTOR_BYTES = 4
        const val WORD_BYTES = 32

        /** The uint argument names that are an amount of the contract's token when the entry marks them tokenRef. */
        val AMOUNT_PARAMS = setOf("amount", "value", "wad")

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

        /** A uint argument as the review lists it: the exact integer, and for a known token its formatted amount as well. */
        fun amountText(raw: BigInteger, token: TokenEntry?): String =
            if (token == null) raw.toString() else "$raw (${Amounts.format(raw, token.decimals)} ${token.symbol})"

        /** i when [path] is exactly m/44'/60'/0'/0/i, otherwise null. */
        fun accountIndexOf(path: List<PathStep>): Int? {
            if (path.size != ACCOUNT_PATH_LENGTH) return null
            if (path.subList(0, ACCOUNT_PATH_PREFIX.size) != ACCOUNT_PATH_PREFIX) return null
            val last = path[ACCOUNT_PATH_PREFIX.size]
            return if (last.hardened) null else last.index
        }

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

    }
}

/**
 * True for a character a reader can check by eye. Line breaks and tabs are fine. Controls, invisible format characters
 * such as a right-to-left override, private-use and unassigned characters are not, so the message shows its bytes.
 */
internal fun isPrintable(char: Char): Boolean = when (char) {
    '\n', '\r', '\t' -> true
    else -> when (Character.getType(char)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        -> false
        else -> true
    }
}
