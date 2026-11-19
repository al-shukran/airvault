package app.airvault.chains.evm.signer

import app.airvault.abi.AbiDecoder
import app.airvault.abi.CallDecode
import app.airvault.abi.WhitelistParam
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.FieldPath
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SectionStyle
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.airvault.core.model.checked
import app.airvault.core.model.isPrintable
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.ParserLimits
import app.lensly.core.registry.AbiRows
import app.lensly.core.registry.Amounts
import app.lensly.core.registry.CallSteps
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** The outcome of [EvmReviewBuilder.review]: the review the owner reads, or the reason the request is refused. */
sealed interface EvmReviewResult {
    class Ok(val review: Review) : EvmReviewResult

    /** [call] is set when the refusal is for a contract call Lensly cannot decode, so the refusal can show its raw data. */
    class Refused(val reason: String, val call: UndecodedCall? = null) : EvmReviewResult
}

/**
 * A contract call Lensly cannot decode, as its refusal shows it: the contract, the network, the native value, the selector, and the calldata
 * after the selector in 32-byte words. Hex has no 0x prefix. The last word is shorter when the calldata does not end on a word boundary.
 * [proxy] is the implementation a whitelisted proxy is bound to, and null for any other call.
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
 * Builds the review of an EVM request (spec 3.2) in the core model. It is the review the app's sign pipeline shows today: the same rows, the
 * same warnings and the same refusals. Each DANGER warning names the check the owner ticks before signing ([Warning.acknowledge]). A BLOCK
 * denylist match refuses the request, as it does today. Nothing here signs or reads a key.
 *
 * [sender] is the address of the account that signs. [decodeCall] reads the arguments of a whitelisted call. The app passes the Parser
 * Sandbox's decoder, so that calldata is read in the sandbox, and the signed whitelist then decides the rest.
 */
class EvmReviewBuilder(
    private val registry: Registry,
    private val decodeCall: (ByteArray, List<WhitelistParam>) -> CallDecode,
    private val proxyPolicy: ProxyPolicy = ProxyPolicy.DEFAULT,
    private val unknownCalls: UnknownCallPolicy = UnknownCallPolicy.REFUSE,
) {
    /** The review of [parsed] for the account at [sender], or the refusal that says why it cannot be shown. */
    fun review(parsed: ParsedRequest, sender: String): EvmReviewResult = try {
        val request = parsed.request
        // The size and origin checks come first, in the order the sign pipeline runs them, so a request is refused for the same reason.
        if (request.signData.size > EthSignRequest.MAX_SIGN_DATA_BYTES) throw Refusal(SIGN_DATA_TOO_LARGE)
        val origin = request.origin
        if (origin != null && !origin.all(::isPrintable)) throw Refusal(UNREADABLE_ORIGIN)
        val chainId = request.chainId ?: throw Refusal(NO_NETWORK)
        EvmReviewResult.Ok(Build(parsed, sender, chainId).build().checked())
    } catch (refusal: Refusal) {
        EvmReviewResult.Refused(refusal.reason, refusal.call)
    }

    /** One review as it is built. Its rows, warnings and coverage collect here until [build] returns the review. */
    private inner class Build(private val parsed: ParsedRequest, private val sender: String, private val chainId: Long) {
        private val request = parsed.request
        private val network: NetworkEntry? = registry.network(chainId)
        private val networkName = network?.name ?: "Unknown network (chain id $chainId)"
        private val chain = ChainId("eip155:$chainId")
        private val header = ArrayList<ReviewRow>()
        private val sections = ArrayList<ReviewSection>()
        private val facts = ArrayList<ReviewRow>()
        private val warnings = ArrayList<Warning>()
        private val decoded = HashSet<FieldPath>()
        private val excluded = HashMap<FieldPath, String>()

        init {
            header += ReviewRow("From", DisplayValue.Address(chain, sender), null)
            request.origin?.let { origin -> header += ReviewRow(CLAIMED_ORIGIN_LABEL, DisplayValue.Text(origin), decode("evm.origin")) }
        }

        fun build(): Review = when (request.dataType) {
            DataType.PERSONAL_MESSAGE -> message()
            DataType.TYPED_DATA -> typed()
            else -> transaction()
        }

        private fun message(): Review {
            val bytes = request.signData
            header += ReviewRow("Type", DisplayValue.Text(PERSONAL_MESSAGE_TYPE), null)
            sections += ReviewSection(
                "Message",
                listOf(
                    ReviewRow("Message", messageValue(bytes), decode("evm.message")),
                    ReviewRow("Size", DisplayValue.Text(sizeText(bytes)), decode("evm.message")),
                ),
            )
            warnings += Warning(Danger.CAUTION, CODE_MESSAGE, PERSONAL_MESSAGE_WARNING)
            return assemble(SIGN_MESSAGE_FUNCTION, listOf(SummaryPart.Text("Signs a message with this account.")), heading = "Request")
        }

        private fun typed(): Review {
            val typedData = when (val result = parsed.typed) {
                is TypedDataParser.Result.Ok -> result.data
                is TypedDataParser.Result.Rejected -> throw Refusal(result.reason)
                null -> throw Refusal(CANNOT_BE_READ)
            }
            val domainChain = typedData.domainChainId ?: throw Refusal(NO_DOMAIN_CHAIN)
            if (domainChain != BigInteger.valueOf(chainId)) {
                throw Refusal("The typed data is for chain $domainChain, but the request names chain $chainId")
            }
            if (network == null) throw Refusal(unknownTypedChain(chainId))
            val verifyingContract = (typedData.domain.fields["verifyingContract"] as? TypedValue.BytesValue)
                ?.let { checksum(it.bytes.toHex()) }
            val denied = registry.typedDenylistMatches(chainId, verifyingContract, typedData.primaryType)
            denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { throw Refusal(it.reason) }
            denied.forEach { warnings += denylistWarning(it, field = null) }
            header += ReviewRow("Type", DisplayValue.Text(TYPED_DATA_TYPE), null)
            val part = typedPart(typedData, registry, chainId, chain, networkName)
            decoded += part.decoded
            warnings += part.warnings
            if (part.unknownToken) warnings += registryWarning(TOKEN_UNREGISTERED, field = null)
            sections += ReviewSection("Domain", part.domain)
            if (part.summary.isNotEmpty()) sections += ReviewSection("Summary", part.summary)
            sections += ReviewSection("Message", part.fields)
            val summary = listOf(SummaryPart.Text("Signs typed data of type ${typedData.primaryType}."))
            return assemble(typedData.primaryType, summary, heading = "Request")
        }

        private fun transaction(): Review {
            val tx = when (val result = parsed.tx) {
                is UnsignedTx.Result.Ok -> result.tx
                is UnsignedTx.Result.Rejected -> throw Refusal(result.reason)
                null -> throw Refusal(CANNOT_BE_READ)
            }
            // The review shows the fields of tx, so they must rebuild the bytes that get signed. Otherwise it shows another transaction.
            if (tx.encode()?.contentEquals(request.signData) != true) throw Refusal(CANNOT_BE_READ)
            if (tx.chainId != BigInteger.valueOf(chainId)) {
                throw Refusal("The transaction is for chain ${tx.chainId}, but the request is for chain $chainId")
            }
            if (tx.nonce.bitLength() >= Long.SIZE_BITS) throw Refusal("The transaction nonce is too large to show")
            val to = tx.to ?: throw Refusal("Contract creation is not supported")
            val calldata = Numeric.hexStringToByteArray(tx.data)
            val multicall = parsed.multicall
            val call = parsed.call
            val (title, summary) = when {
                calldata.isEmpty() -> nativeTransaction(tx, to)
                multicall != null -> multicallTransaction(tx, to, calldata, multicall)
                call == null -> gapTransaction(tx, to, calldata)
                else -> callTransaction(tx, to, calldata, call)
            }
            // The denylist is checked after the call, as the sign pipeline checks it: a BLOCK match refuses, and a WARN match needs the owner's check.
            val denied = registry.denylistMatches(chainId, to, calldata)
            denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { throw Refusal(it.reason) }
            denied.forEach { warnings += denylistWarning(it, FieldPath("evm.to")) }
            header += ReviewRow("Transaction Type", DisplayValue.Text(txTypeText(tx.type)), decode("evm.type"))
            header += ReviewRow("Nonce", DisplayValue.Number(tx.nonce), decode("evm.nonce"))
            facts += feeRows(tx)
            feeNote(tx)?.let { facts += ReviewRow("Fee Note", DisplayValue.Text(it), null) }
            if (tx.accessList.isNotEmpty()) sections += accessListSection(tx.accessList)
            return assemble(title, summary, heading = "Transaction")
        }

        /** A native transfer: its value in the network's coin, to [to]. A native transfer shows its value even at zero. */
        private fun nativeTransaction(tx: UnsignedTx, to: String): Pair<String, List<SummaryPart>> {
            targetRow("To", to)
            valueRow(tx.value, always = true)
            val summary = listOf(
                SummaryPart.Text("Sends "),
                SummaryPart.Mono(nativeAmount(tx.value)),
                SummaryPart.Text(" to "),
                SummaryPart.Counterparty(to),
                SummaryPart.Text("."),
            )
            return "Native Transfer" to summary
        }

        /** A decoded contract call on [to]. A token call sends no native currency, so the request is refused when it does. */
        private fun callTransaction(tx: UnsignedTx, to: String, calldata: ByteArray, call: ContractCall): Pair<String, List<SummaryPart>> {
            // The labels are shown only when the decoded call is exactly the calldata that gets signed.
            if (call.encode()?.contentEquals(calldata) != true) throw Refusal(CANNOT_BE_READ)
            if (tx.value.signum() != 0) throw Refusal("Token calls must not send native currency")
            targetRow("Contract", to)
            functionRow(call.signature)
            valueRow(tx.value, always = false)
            // A contract outside the registry is an unknown token. Its decimals are not known, so its amounts stay raw.
            val part = callPart(call, to, registry.token(chainId, to))
            sections += ReviewSection("Arguments", rowsOf(part.params, "evm.data", 0) + noteRows(part.note))
            part.warnings.forEach { warnings += it.toWarning("evm.data") }
            if (part.unregistered) warnings += registryWarning(TOKEN_UNREGISTERED, FieldPath("evm.to"))
            return part.function to part.sentence
        }

        /**
         * A call Lensly cannot decode. The signed whitelist decides it: an approved call is reviewed with its approved meaning and its
         * arguments, and an unapproved one is refused, or reviewed under the Warn policy with its raw data.
         */
        private fun gapTransaction(tx: UnsignedTx, to: String, calldata: ByteArray): Pair<String, List<SummaryPart>> {
            targetRow("Contract", to)
            return when (val gap = whitelisted(to, calldata, tx.value)) {
                is Gap.Approved -> {
                    functionRow(gap.signature)
                    valueRow(tx.value, always = false)
                    sections += ReviewSection("Arguments", rowsOf(approvedDetails(gap, calldata, tx.value), "evm.data", 0))
                    gap.steps?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                        sections += ReviewSection("Steps", lines.map { ReviewRow(it, null, null) }, SectionStyle.Steps)
                    }
                    warnings += approvedWarnings(gap, FieldPath("evm.data.function"))
                    val summary = listOf(
                        SummaryPart.Text("Calls ${gap.function} on "),
                        SummaryPart.Counterparty(to),
                        SummaryPart.Text(", which the signed list approves."),
                    )
                    gap.function to summary
                }
                is Gap.Unknown -> {
                    valueRow(tx.value, always = false)
                    sections += ReviewSection("Arguments", rowsOf(unknownParams(calldata, gap.selector), "evm.data", 0))
                    warnings += Warning(
                        Danger.DANGER,
                        CODE_UNKNOWN_CALL,
                        UNKNOWN_CALL_WARNING,
                        FieldPath("evm.data.selector"),
                        ACK_UNKNOWN_CALL,
                    )
                    "0x${gap.selector}" to listOf(SummaryPart.Text("Calls an unknown function on "), SummaryPart.Counterparty(to), SummaryPart.Text("."))
                }
            }
        }

        /** A multicall. Its calldata must be exactly what its calls encode to, and each call is decided where it sits. */
        private fun multicallTransaction(
            tx: UnsignedTx,
            to: String,
            calldata: ByteArray,
            multicall: Multicall,
        ): Pair<String, List<SummaryPart>> {
            val shape = encodesAs(calldata, multicall) ?: throw Refusal(CANNOT_BE_READ)
            if (multicall.deepestLevel() > ParserLimits.MAX_NESTED_DEPTH) throw Refusal(TOO_DEEP_REFUSAL)
            if (multicall.callCount() > ParserLimits.MAX_NESTED_CALLS) throw Refusal(TOO_MANY_CALLS_REFUSAL)
            targetRow("Contract", to)
            functionRow(shape.signature)
            multicall.deadline?.let { header += ReviewRow(DEADLINE_LABEL, DisplayValue.Number(it), decode("evm.data.deadline")) }
            valueRow(tx.value, always = false)
            val rows = ArrayList<ReviewRow>()
            checkCalls(tx, to, shape, multicall, level = 1, parent = "evm.data", rows = rows)
            if (rows.isNotEmpty()) sections += ReviewSection("Calls", rows)
            val summary = listOf(SummaryPart.Text("Runs ${multicall.callCount()} calls in one transaction."))
            return shape.function to summary
        }

        /**
         * Decides each call of [multicall], whose calldata has [shape], and lists it in [rows] at [level]. A nested batch is checked against its
         * own calldata, and its calls one level down. A call that fails any check refuses the whole request.
         */
        private fun checkCalls(
            tx: UnsignedTx,
            to: String,
            shape: MulticallShape,
            multicall: Multicall,
            level: Int,
            parent: String,
            rows: MutableList<ReviewRow>,
        ) {
            multicall.calls.forEachIndexed { index, inner ->
                // A Multicall3 call names its own contract. Any other call goes to the contract its batch goes to.
                val contract = inner.target ?: to
                val prefix = "$parent.call[$index]"
                // Every call of the batch meets the denylist, as the request does: a BLOCK match refuses it, and a WARN match stays on its row.
                val denied = registry.denylistMatches(chainId, contract, inner.calldata)
                denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { throw Refusal(it.reason) }
                denied.forEach { warnings += denylistWarning(it, FieldPath("$prefix.function")) }
                val target = inner.target?.let(::checksum)
                val flags = flagsOf(shape, inner)
                val nested = inner.nested
                val lone = inner.call
                when {
                    nested != null -> {
                        val nestedShape = encodesAs(inner.calldata, nested) ?: throw Refusal(CANNOT_BE_READ)
                        callRows(rows, prefix, index, level, nestedShape.function, target, flags, note = null)
                        nested.deadline?.let { rows += ReviewRow(DEADLINE_LABEL, DisplayValue.Number(it), decode("$prefix.deadline"), level) }
                        checkCalls(tx, contract, nestedShape, nested, level + 1, prefix, rows)
                    }
                    lone != null -> {
                        // The labels are shown only when the decoded call is exactly the calldata that gets signed.
                        if (lone.encode()?.contentEquals(inner.calldata) != true) throw Refusal(CANNOT_BE_READ)
                        val part = callPart(lone, contract, registry.token(chainId, contract))
                        callRows(rows, prefix, index, level, part.function, target, flags + part.params, part.note)
                        part.warnings.forEach { warnings += it.toWarning(prefix) }
                        if (part.unregistered) warnings += registryWarning(TOKEN_UNREGISTERED, FieldPath("$prefix.function"))
                    }
                    else -> when (val gap = whitelisted(contract, inner.calldata, inner.value ?: tx.value)) {
                        is Gap.Approved -> {
                            callRows(
                                rows,
                                prefix,
                                index,
                                level,
                                gap.function,
                                target,
                                flags + approvedDetails(gap, inner.calldata, inner.value ?: tx.value),
                                note = null,
                            )
                            gap.steps?.lines?.forEach { rows += ReviewRow(it, null, null, level) }
                            warnings += approvedWarnings(gap, FieldPath("$prefix.function"))
                        }
                        is Gap.Unknown -> {
                            callRows(
                                rows,
                                prefix,
                                index,
                                level,
                                "0x${gap.selector}",
                                target,
                                flags + unknownParams(inner.calldata, gap.selector),
                                note = null,
                            )
                            warnings += Warning(
                                Danger.DANGER,
                                CODE_UNKNOWN_CALL,
                                UNKNOWN_CALL_WARNING,
                                FieldPath("$prefix.selector"),
                                ACK_UNKNOWN_CALL,
                            )
                        }
                    }
                }
            }
        }

        /**
         * What the signed whitelist decides for an undecodable call to [contract] that sends [value]. An approved call carries its arguments,
         * decoded and checked against the calldata before they are shown. A refusal names its reason and the call's raw data.
         */
        private fun whitelisted(contract: String, calldata: ByteArray, value: BigInteger): Gap {
            val entry = whitelistEntry(contract, calldata)
            val proxy = entry?.takeIf { it.upgradeable }?.proxy
            val undecoded = undecodedCall(contract, value, calldata, proxy)
            if (entry == null) {
                if (unknownCalls == UnknownCallPolicy.REFUSE) throw Refusal(UNKNOWN_CALL_REFUSAL, undecoded)
                return Gap.Unknown(calldata.copyOfRange(0, minOf(SELECTOR_BYTES, calldata.size)).toHex())
            }
            if (entry.upgradeable && proxyPolicy == ProxyPolicy.BIND_TO_IMPLEMENTATION) throw Refusal(BOUND_PROXY_REFUSAL, undecoded)
            if (entry.upgradeable && proxyPolicy == ProxyPolicy.REFUSE_ALL_PROXIES) throw Refusal(ANY_PROXY_REFUSAL, undecoded)
            val arguments = when (val result = decodeCall(calldata, entry.params)) {
                is CallDecode.Ok -> result.params
                is CallDecode.Refused -> throw Refusal(result.reason, undecoded)
            }
            // The decoder must answer with exactly the params the entry declares, in their order.
            if (arguments.map { it.param } != entry.params) throw Refusal(CANNOT_BE_READ, undecoded)
            // The labels are shown only when the decoded values are exactly the arguments that get signed.
            if (AbiDecoder.encode(arguments)?.contentEquals(argumentsOf(calldata)) != true) throw Refusal(CANNOT_BE_READ, undecoded)
            val token = registry.token(chainId, contract)
            val steps = CallSteps.describe(registry, chainId, contract, entry.selector, entry.signature, sender, value, arguments)
            val params = arguments.flatMapIndexed { index, argument ->
                AbiRows.of(argument) { amount ->
                    if (token != null && argument.param.name in AMOUNT_PARAMS) amountText(amount, token) else null
                }.map { (label, text) -> ParamRow(label, DisplayValue.Text(text), "arg[$index]") }
            }
            return Gap.Approved(
                signature = entry.signature,
                function = entry.signature.substringBefore('('),
                meaning = entry.meaning,
                params = params,
                proxy = proxy,
                steps = steps,
            )
        }

        /**
         * The rows of one call's arguments: the approved meaning first, then the arguments, then the native value when it is not zero, the
         * selector, the signed calldata in full, and each word after the selector. The arguments preview a bytes value, so the calldata
         * row is what shows every byte of it. The proxy binding comes last.
         */
        private fun approvedDetails(gap: Gap.Approved, calldata: ByteArray, value: BigInteger): List<ParamRow> = buildList {
            add(ParamRow("Approved Meaning", DisplayValue.Text(gap.meaning), null))
            addAll(gap.params)
            if (value.signum() != 0) add(ParamRow("Value", DisplayValue.Text(nativeAmount(value)), null))
            add(ParamRow("Selector", DisplayValue.Text("0x" + calldata.copyOfRange(0, SELECTOR_BYTES).toHex()), "selector"))
            add(calldataRow(calldata))
            wordsOf(calldata).forEachIndexed { index, word -> add(ParamRow("Word ${index + 1}", DisplayValue.Bytes(word), null)) }
            gap.proxy?.implementationAddress?.let { add(ParamRow("Implementation", DisplayValue.Address(chain, it), null)) }
            gap.proxy?.implementationCodeHash?.let { add(ParamRow("Implementation Code Hash", DisplayValue.Text(it), null)) }
        }

        /** The warnings an approved call earns: its signed meaning, a proxy under Warn and confirm, and any step Lensly cannot read. */
        private fun approvedWarnings(gap: Gap.Approved, field: FieldPath): List<Warning> = buildList {
            add(
                Warning(
                    Danger.CAUTION,
                    CODE_WHITELIST,
                    "Lensly cannot read this call. The signed list approves it: ${gap.meaning}",
                    field,
                    ACK_WHITELIST,
                ),
            )
            if (gap.proxy != null) add(Warning(Danger.DANGER, CODE_PROXY, PROXY_WARNING, field, ACK_UPGRADE))
            gap.steps?.warnings?.forEach { add(Warning(Danger.CAUTION, CODE_STEPS, it, field)) }
        }

        /** The review of one call, as the owner reads it: what it does, its arguments and the warnings it earns. */
        private fun callPart(call: ContractCall, contract: String, token: TokenEntry?): CallPart {
            val symbol = token?.symbol
            val note = if (token == null) UNKNOWN_DECIMALS_NOTE else null
            val unregistered = token == null
            return when (call) {
                is ContractCall.Transfer -> CallPart(
                    function = call.name,
                    params = listOf(addressParam("to", call.to), ParamRow("value", text(amountText(call.value, token)), "value")),
                    warnings = emptyList(),
                    note = note,
                    unregistered = unregistered,
                    sentence = listOf(plain("Sends "), mono(displayAmount(call.value, token)), plain(" to "), party(call.to), plain(".")),
                )
                is ContractCall.Approve -> CallPart(
                    function = call.name,
                    params = listOf(addressParam("spender", call.spender), ParamRow("value", text(amountText(call.value, token)), "value")),
                    warnings = unlimitedWarnings(call.unlimited, symbol, "value"),
                    note = note,
                    unregistered = unregistered,
                    sentence = listOf(
                        plain("Approves "),
                        party(call.spender),
                        plain(" to spend "),
                        amountPart(call.value, token, call.unlimited),
                        plain("."),
                    ),
                )
                is ContractCall.TransferFrom -> if (token != null) {
                    CallPart(
                        function = call.name,
                        params = listOf(
                            addressParam("from", call.from),
                            addressParam("to", call.to),
                            ParamRow("value", text(amountText(call.value, token)), "value"),
                        ),
                        warnings = emptyList(),
                        note = null,
                        unregistered = false,
                        sentence = listOf(
                            plain("Moves "),
                            mono(displayAmount(call.value, token)),
                            plain(" from "),
                            party(call.from),
                            plain(" to "),
                            party(call.to),
                            plain("."),
                        ),
                    )
                } else {
                    // The contract is not known, so its third argument is a token amount or a token id. Neither is shown as an amount.
                    CallPart(
                        function = "${call.name} (token or NFT)",
                        params = listOf(
                            addressParam("from", call.from),
                            addressParam("to", call.to),
                            ParamRow("amount or tokenId", DisplayValue.Number(call.value), "value"),
                        ),
                        warnings = emptyList(),
                        note = null,
                        unregistered = true,
                        sentence = listOf(plain("Moves a token or NFT from "), party(call.from), plain(" to "), party(call.to), plain(".")),
                    )
                }
                is ContractCall.IncreaseAllowance -> CallPart(
                    function = call.name,
                    params = listOf(
                        addressParam("spender", call.spender),
                        ParamRow("addedValue", text(amountText(call.addedValue, token)), "addedValue"),
                    ),
                    warnings = unlimitedWarnings(call.unlimited, symbol, "addedValue"),
                    note = note,
                    unregistered = unregistered,
                    sentence = listOf(
                        plain("Raises the allowance of "),
                        party(call.spender),
                        plain(" by "),
                        amountPart(call.addedValue, token, call.unlimited),
                        plain("."),
                    ),
                )
                is ContractCall.DecreaseAllowance -> CallPart(
                    function = call.name,
                    params = listOf(
                        addressParam("spender", call.spender),
                        ParamRow("subtractedValue", text(amountText(call.subtractedValue, token)), "subtractedValue"),
                    ),
                    warnings = emptyList(),
                    note = note,
                    unregistered = unregistered,
                    sentence = listOf(
                        plain("Lowers the allowance of "),
                        party(call.spender),
                        plain(" by "),
                        mono(displayAmount(call.subtractedValue, token)),
                        plain("."),
                    ),
                )
                is ContractCall.SetApprovalForAll -> CallPart(
                    function = call.name,
                    params = listOf(addressParam("operator", call.operator), ParamRow("approved", text("${call.approved}"), "approved")),
                    warnings = if (call.approved) {
                        listOf(CallWarning(Danger.DANGER, CODE_ALL_NFTS, ALL_NFTS_WARNING, ACK_ALL_NFTS, "approved"))
                    } else {
                        emptyList()
                    },
                    note = null,
                    unregistered = unregistered,
                    sentence = if (call.approved) {
                        listOf(plain("Grants "), party(call.operator), plain(" control of all tokens of "), party(contract), plain("."))
                    } else {
                        listOf(plain("Removes "), party(call.operator), plain(" as operator of "), party(contract), plain("."))
                    },
                )
                is ContractCall.SafeTransferFrom -> CallPart(
                    function = call.name,
                    params = listOf(
                        addressParam("from", call.from),
                        addressParam("to", call.to),
                        ParamRow("tokenId", DisplayValue.Number(call.tokenId), "tokenId"),
                    ),
                    warnings = emptyList(),
                    note = null,
                    unregistered = unregistered,
                    sentence = listOf(
                        plain("Sends token #${call.tokenId} from "),
                        party(call.from),
                        plain(" to "),
                        party(call.to),
                        plain("."),
                    ),
                )
            }
        }

        /** The unlimited-approval warning of a call whose amount is unlimited, in the field [field] of the call. */
        private fun unlimitedWarnings(unlimited: Boolean, symbol: String?, field: String): List<CallWarning> =
            if (unlimited) listOf(CallWarning(Danger.DANGER, CODE_UNLIMITED, unlimitedApprovalWarning(symbol), ACK_UNLIMITED, field)) else emptyList()

        /** A call's address argument, shown in full so the Address Book can name it. */
        private fun addressParam(label: String, address: String): ParamRow =
            ParamRow(label, DisplayValue.Address(chain, checksum(address)), label)

        private fun flagsOf(shape: MulticallShape, inner: InnerCall): List<ParamRow> = buildList {
            if (shape.hasAllowFailure) add(ParamRow("allowFailure (bool)", DisplayValue.Text("${inner.allowFailure}"), "allowFailure"))
            if (shape.hasValue) inner.value?.let { add(ParamRow("value (uint256)", DisplayValue.Text(nativeAmount(it)), "value")) }
        }

        /**
         * The rows of one call of a batch: its number and function, then its contract and details one level deeper. [details] name the
         * fields they show under [prefix], so each one is decoded.
         */
        private fun callRows(
            rows: MutableList<ReviewRow>,
            prefix: String,
            index: Int,
            level: Int,
            function: String,
            target: String?,
            details: List<ParamRow>,
            note: String?,
        ) {
            rows += ReviewRow("Call ${index + 1}", DisplayValue.Text(function), decode("$prefix.function"), level - 1)
            target?.let { rows += ReviewRow("Contract", DisplayValue.Address(chain, it), decode("$prefix.target"), level) }
            rows += rowsOf(details, prefix, level)
            note?.let { rows += ReviewRow("Amount Note", DisplayValue.Text(it), null, level) }
        }

        /** The rows of [params], each shown under [prefix] at [depth]. A row with no field shows no decoded field. */
        private fun rowsOf(params: List<ParamRow>, prefix: String, depth: Int): List<ReviewRow> = params.map { param ->
            ReviewRow(param.label, param.value, param.field?.let { decode("$prefix.$it") }, depth)
        }

        /** The native value of a transaction. A call that sends none shows no amount, and its coverage says why. */
        private fun valueRow(value: BigInteger, always: Boolean) {
            val field = decode("evm.value")
            if (!always && value.signum() == 0) {
                excluded[field] = "a call that sends no native currency shows no amount"
                return
            }
            header += ReviewRow("Amount", DisplayValue.Text(nativeAmount(value)), field)
            if (network == null) header += ReviewRow("Amount Note", DisplayValue.Text(assumedAmountNote(value)), null)
        }

        private fun targetRow(label: String, to: String) {
            header += ReviewRow(label, DisplayValue.Address(chain, to), decode("evm.to"))
        }

        private fun functionRow(signature: String) {
            header += ReviewRow("Function", DisplayValue.Text(signature), decode("evm.data.function"))
        }

        private fun accessListSection(entries: List<AccessListEntry>): ReviewSection {
            val rows = ArrayList<ReviewRow>()
            val field = decode("evm.accessList")
            for (entry in entries) {
                rows += ReviewRow("Address", DisplayValue.Address(chain, checksum(entry.address)), field)
                for (key in entry.storageKeys) rows += ReviewRow("Storage Key", DisplayValue.Text(key, mono = true), field, depth = 1)
            }
            return ReviewSection("Access List", rows)
        }

        /** The fee rows of [tx], in the units of the network. On an unknown network the fee stays in base units. */
        private fun feeRows(tx: UnsignedTx): List<ReviewRow> = buildList {
            add(ReviewRow("Gas Limit", DisplayValue.Number(tx.gasLimit), decode("evm.gasLimit")))
            when (tx.type) {
                TxType.LEGACY_EIP155, TxType.EIP2930 -> {
                    val gasPrice = checkNotNull(tx.gasPrice)
                    add(ReviewRow("Gas Price", DisplayValue.Text(gwei(gasPrice)), decode("evm.gasPrice")))
                    add(ReviewRow("Max Fee", DisplayValue.Text(nativeAmount(tx.gasLimit * gasPrice)), null))
                }
                TxType.EIP1559 -> {
                    val maxFee = checkNotNull(tx.maxFeePerGas)
                    add(ReviewRow("Max Fee per Gas", DisplayValue.Text(gwei(maxFee)), decode("evm.maxFeePerGas")))
                    add(
                        ReviewRow(
                            "Max Priority Fee",
                            DisplayValue.Text(gwei(checkNotNull(tx.maxPriorityFeePerGas))),
                            decode("evm.maxPriorityFeePerGas"),
                        ),
                    )
                    add(ReviewRow("Max Fee", DisplayValue.Text(nativeAmount(tx.gasLimit * maxFee)), null))
                }
            }
        }

        /** The note under the fee. A rollup gets the L2 caveat. An unknown chain gets the assumed value and a caveat. */
        private fun feeNote(tx: UnsignedTx): String? = when {
            network == null -> unknownFeeNote(maxFeeWei(tx))
            network.feeModel == FeeModel.L2_ROLLUP -> L2_FEE_NOTE
            else -> null
        }

        /** A native amount in the network's coin. An unknown network has no known coin, so it stays in base units. */
        private fun nativeAmount(wei: BigInteger): String =
            network?.let { "${Amounts.format(wei, it.nativeDecimals)} ${it.nativeSymbol}" } ?: "$wei $UNKNOWN_UNIT"

        private fun whitelistEntry(contract: String, calldata: ByteArray): WhitelistEntry? {
            if (calldata.size < SELECTOR_BYTES) return null
            return registry.whitelist(chainId, contract, "0x" + calldata.copyOfRange(0, SELECTOR_BYTES).toHex())
        }

        /** What a refusal or an unapproved review of an undecodable call shows: its contract, network, value, selector and words. */
        private fun undecodedCall(contract: String, value: BigInteger, calldata: ByteArray, proxy: ProxyBinding?): UndecodedCall =
            UndecodedCall(
                target = checksum(contract),
                networkName = networkName,
                chainId = chainId,
                value = nativeAmount(value),
                selector = calldata.copyOfRange(0, minOf(SELECTOR_BYTES, calldata.size)).toHex(),
                words = wordsOf(calldata).map { it.toHex() },
                proxy = proxy,
            )

        /** Marks [path] as a field the review shows, and returns it. */
        private fun decode(path: String): FieldPath = FieldPath(path).also { decoded += it }

        /** Names the network, shows its coverage, and puts the request's rows first. A chain outside the registry is a registry warning. */
        private fun assemble(title: String, summary: List<SummaryPart>, heading: String): Review {
            excluded[decode("evm.chainId")] = "the header names the network by its chain ID"
            if (network == null) warnings += registryWarning(NETWORK_UNREGISTERED, field = null)
            sections.add(0, ReviewSection(heading, header))
            return Review(
                title = title,
                chain = chain,
                network = NetworkLabel(
                    name = networkName,
                    testnet = network?.testnet == true,
                    detail = network?.let { "Chain ID $chainId" },
                ),
                summary = summary,
                facts = facts,
                sections = sections,
                warnings = warnings,
                coverage = Coverage(decoded = decoded, excluded = excluded),
            )
        }
    }

    /** A refusal raised while a review is built. It carries the reason the request is refused, and the call that caused it, if any. */
    private class Refusal(val reason: String, val call: UndecodedCall? = null) : RuntimeException(reason, null, false, false)
}

/** A row of a call's details: a label, its value, and the name of the field it shows relative to the call. A row with no field shows none. */
private class ParamRow(val label: String, val value: DisplayValue?, val field: String?)

/** A warning on a call, with its field named relative to the call, so the batch can place it under the call's own prefix. */
private class CallWarning(val level: Danger, val code: String, val message: String, val acknowledge: String, val field: String) {
    fun toWarning(prefix: String): Warning = Warning(level, code, message, FieldPath("$prefix.$field"), acknowledge)
}

/** One call as the review reads it. A lone call and a call of a batch share it. */
private class CallPart(
    val function: String,
    val params: List<ParamRow>,
    val warnings: List<CallWarning>,
    val note: String?,
    val unregistered: Boolean,
    val sentence: List<SummaryPart>,
)

/** What the signed whitelist decides for an undecodable call: approved, with its facts, or unapproved, with its selector. */
private sealed interface Gap {
    class Approved(
        val signature: String,
        val function: String,
        val meaning: String,
        val params: List<ParamRow>,
        val proxy: ProxyBinding?,
        val steps: CallSteps?,
    ) : Gap

    class Unknown(val selector: String) : Gap
}

private fun unknownParams(calldata: ByteArray, selector: String): List<ParamRow> = listOf(
    ParamRow("Selector", DisplayValue.Text("0x$selector"), "selector"),
    calldataRow(calldata),
)

/** The signed calldata in full, as its own row. A call whose arguments are shown carries this row, so no byte is only a preview. */
private fun calldataRow(calldata: ByteArray): ParamRow = ParamRow("Calldata", DisplayValue.Bytes(calldata), "calldata")

/** The calldata after its selector, in 32-byte words. The last word is shorter when the calldata does not end on a word boundary. */
private fun wordsOf(calldata: ByteArray): List<ByteArray> =
    (SELECTOR_BYTES until calldata.size step WORD_BYTES).map { start ->
        calldata.copyOfRange(start, minOf(start + WORD_BYTES, calldata.size))
    }

private fun noteRows(note: String?): List<ReviewRow> = note?.let { listOf(ReviewRow("Amount Note", DisplayValue.Text(it), null)) }.orEmpty()

private fun registryWarning(message: String, field: FieldPath?): Warning =
    Warning(Danger.CAUTION, CODE_REGISTRY, message, field, ACK_REGISTRY)

private fun denylistWarning(entry: DenylistEntry, field: FieldPath?): Warning =
    Warning(Danger.DANGER, CODE_DENYLIST, entry.reason, field, ACK_DENYLIST)

private fun unknownTypedChain(chainId: Long): String =
    "The typed data is for chain $chainId, which is not in Lensly's network registry, so it is refused."

private fun amountText(raw: BigInteger, token: TokenEntry?): String =
    if (token == null) "$raw $UNKNOWN_UNIT (unknown token, decimals unknown)" else "$raw (${Amounts.format(raw, token.decimals)} ${token.symbol})"

private fun displayAmount(raw: BigInteger, token: TokenEntry?): String =
    if (token == null) "$raw $UNKNOWN_UNIT" else "${Amounts.format(raw, token.decimals)} ${token.symbol}"

private fun amountPart(raw: BigInteger, token: TokenEntry?, unlimited: Boolean): SummaryPart =
    if (unlimited) plain("an unlimited amount") else mono(displayAmount(raw, token))

private fun plain(text: String): SummaryPart = SummaryPart.Text(text)

private fun mono(text: String): SummaryPart = SummaryPart.Mono(text)

private fun party(address: String): SummaryPart = SummaryPart.Counterparty(checksum(address))

private fun text(text: String): DisplayValue = DisplayValue.Text(text)

private fun txTypeText(type: TxType): String = when (type) {
    TxType.LEGACY_EIP155 -> "Legacy"
    TxType.EIP2930 -> "EIP-2930"
    TxType.EIP1559 -> "EIP-1559"
}

private fun gwei(wei: BigInteger): String = "${Amounts.format(wei, GWEI_DECIMALS)} gwei"

/** The note under an unknown chain's native amount: [wei] read with 18 decimals, labelled as an assumption. */
private fun assumedAmountNote(wei: BigInteger): String =
    "≈ ${assumedEighteen(wei)} if 18 decimals (assumption, not verified)"

/** The fee note for an unknown chain: the maximum fee under the 18-decimal assumption, and a warning that the total may be higher. */
private fun unknownFeeNote(maxFee: BigInteger): String =
    "${assumedAmountNote(maxFee)}. The fee model is unknown, so the total may be higher."

/** [wei] as an amount with 18 decimals, with at least one digit after the point, so 10^18 reads as "1.0". */
private fun assumedEighteen(wei: BigInteger): String {
    val text = Amounts.format(wei, ASSUMED_DECIMALS)
    return if ('.' in text) text else "$text.0"
}

/** The most the sender can pay for execution: gas limit times gas price, or times the max fee per gas. */
private fun maxFeeWei(tx: UnsignedTx): BigInteger = tx.gasLimit * checkNotNull(tx.gasPrice ?: tx.maxFeePerGas)

/** The arguments of [calldata], which is what follows its selector. A calldata shorter than a selector has none. */
private fun argumentsOf(calldata: ByteArray): ByteArray = calldata.copyOfRange(minOf(SELECTOR_BYTES, calldata.size), calldata.size)

private fun checksum(address: String): String = Keys.toChecksumAddress(address)

private fun messageValue(bytes: ByteArray): DisplayValue =
    printableMessage(bytes)?.let { DisplayValue.Message(it) } ?: DisplayValue.Bytes(bytes)

/** The message as UTF-8 text, when every character is one a reader can see. Otherwise null. */
private fun printableMessage(bytes: ByteArray): String? = utf8OrNull(bytes)?.takeIf { text -> text.all(::isPrintable) }

private fun utf8OrNull(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}

/** The length of a message: its characters when it reads as text, and its bytes. */
private fun sizeText(bytes: ByteArray): String {
    val text = printableMessage(bytes) ?: return "${bytes.size} bytes"
    return "${text.codePointCount(0, text.length)} characters, ${bytes.size} bytes"
}

/** The warning on an approval that lets the spender take every token. [symbol] is the token, or null when it is unknown. */
internal fun unlimitedApprovalWarning(symbol: String?): String =
    "${ContractCall.UNLIMITED_APPROVAL_PREFIX} this contract can spend all your ${symbol ?: "tokens of this contract"}"

/** The unit of an amount whose coin or token is not known. The amount is then shown as raw base units. */
internal const val UNKNOWN_UNIT = "base units"

/** The note on a token amount whose decimals are not known. */
internal const val UNKNOWN_DECIMALS_NOTE = "Decimals unknown: raw integer amount, not verified"

/** The name of a token that is not in the registry. */
internal const val UNKNOWN_TOKEN_NAME = "Unknown token"

/** The warning on `setApprovalForAll(operator, true)`: the operator controls every token of the contract. */
internal const val ALL_NFTS_WARNING = "Grants control of ALL your NFTs in this collection"

/** The warning on a call Lensly cannot read that the signed whitelist does not approve. */
internal const val UNKNOWN_CALL_WARNING =
    "Unknown contract call. Lensly does not read this call, so it cannot say what it does. Check the contract and the calldata before you sign."

/** The Function of a personal message. A message has no recipient, amount, nonce or fee, and its text is its only parameter. */
internal const val SIGN_MESSAGE_FUNCTION = "Sign Message"

/** The check the owner ticks for a DANGER warning on an unlimited approval, by name. */
internal const val ACK_UNLIMITED = "I understand this approval is unlimited."

/** The check the owner ticks for an unknown call, by name. */
internal const val ACK_UNKNOWN_CALL = "I understand Lensly cannot say what this call does."

/** The check the owner ticks for a request that the denylist warns about, by name. */
internal const val ACK_DENYLIST = "I understand this request matches a known-dangerous request."

/** The check the owner ticks when the chain or the contract is outside the registry. */
internal const val ACK_REGISTRY = "I have checked the chain ID and the contract."

/** The check the owner ticks for an approved call, once the owner has read its signed meaning. */
internal const val ACK_WHITELIST = "I have read what this call is approved to do."

/** The check the owner ticks for an operator grant over every token of a contract. */
internal const val ACK_ALL_NFTS = "I understand this grants control of all NFTs in this collection."

/** The check the owner ticks for a whitelisted proxy under Warn and confirm. */
internal const val ACK_UPGRADE = "I accept that this proxy's code cannot be checked offline."

internal const val CODE_UNLIMITED = "evm.unlimited-approval"
internal const val CODE_ALL_NFTS = "evm.approval-for-all"
internal const val CODE_UNKNOWN_CALL = "evm.unknown-call"
internal const val CODE_DENYLIST = "evm.denylist"
internal const val CODE_REGISTRY = "evm.registry"
internal const val CODE_WHITELIST = "evm.whitelisted-call"
internal const val CODE_PROXY = "evm.proxy"
internal const val CODE_STEPS = "evm.steps"
internal const val CODE_MESSAGE = "evm.personal-message"

private const val SIGN_DATA_TOO_LARGE = "The data to sign is larger than 64 KiB"
private const val NO_NETWORK = "The request does not name a network"
private const val UNREADABLE_ORIGIN = "The request names an origin with a character Lensly cannot show."
private const val NO_DOMAIN_CHAIN = "The typed data names no chain ID in its domain"
private const val UNKNOWN_CALL_REFUSAL = "Unknown Contract Call"
private const val BOUND_PROXY_REFUSAL = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is still the " +
    "approved version, so your Proxy Policy refuses it."
private const val ANY_PROXY_REFUSAL = "This contract is an upgradeable proxy, and your Proxy Policy refuses every proxy."
private const val PROXY_WARNING = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is still the " +
    "version the signed list approves."
private const val NETWORK_UNREGISTERED = "This network is not in Lensly's registry, so Lensly cannot check its coin or decimals."
private const val TOKEN_UNREGISTERED = "This contract is not in Lensly's token registry, so Lensly cannot check its name or decimals."
private const val PERSONAL_MESSAGE_WARNING = "The signature proves you hold this account to whoever checks it, and a site can use it to " +
    "act for you. Sign only a message from a site you trust."
private const val PERSONAL_MESSAGE_TYPE = "Personal Message"
private const val TYPED_DATA_TYPE = "Typed data (EIP-712)"
private const val L2_FEE_NOTE = "L1 data fee is not computable offline; total may be higher"
private const val DEADLINE_LABEL = "deadline (uint256)"

/** The label of the origin a request names. The request sets it, so the review does not vouch for it. */
private const val CLAIMED_ORIGIN_LABEL = "Claimed by request (not verified)"
private const val GWEI_DECIMALS = 9
private const val ASSUMED_DECIMALS = 18
private const val SELECTOR_BYTES = 4
private const val WORD_BYTES = 32
private val AMOUNT_PARAMS = setOf("amount", "value", "wad")
private val TOO_DEEP_REFUSAL = "The calls nest deeper than ${ParserLimits.MAX_NESTED_DEPTH} levels, so Lensly cannot show every call."
private val TOO_MANY_CALLS_REFUSAL = "The transaction holds more than ${ParserLimits.MAX_NESTED_CALLS} calls, so Lensly cannot show every call."
