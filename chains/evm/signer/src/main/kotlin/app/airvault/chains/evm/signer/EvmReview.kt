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
import app.airvault.core.model.isPrintableCodePoint
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.ParserLimits
import app.lensly.core.registry.AbiRows
import app.lensly.core.registry.Amounts
import app.lensly.core.registry.CallSteps
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.OffChainDomain
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.registry.offChainLabelOf
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** The outcome of [EvmReviewBuilder.review]: the review the owner reads, or the reason the request is refused. */
sealed interface EvmReviewResult {
    class Ok(val review: Review) : EvmReviewResult

    /**
     * [rows] are set when the refusal is for a contract call Lensly cannot decode: the whole call as review rows, so the refusal can show its
     * raw data. Each calldata word is a row indented under the "Calldata" heading.
     */
    class Refused(val reason: String, val rows: List<ReviewRow> = emptyList()) : EvmReviewResult
}

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
    /**
     * The review of [parsed] for the account at [sender], or the refusal that says why it cannot be shown. [derivationPath] is the path
     * of the key that signs, which the header shows under the sender when given.
     */
    fun review(parsed: ParsedRequest, sender: String, derivationPath: String? = null): EvmReviewResult = try {
        val request = parsed.request
        // The size and origin checks come first, in the order the sign pipeline runs them, so a request is refused for the same reason.
        if (request.signData.size > EthSignRequest.MAX_SIGN_DATA_BYTES) throw Refusal(SIGN_DATA_TOO_LARGE)
        val origin = request.origin
        if (origin != null && !origin.codePoints().allMatch(::isPrintableCodePoint)) throw Refusal(UNREADABLE_ORIGIN)
        // Typed data is reviewed on the chain its domain names (see reviewChainId). A request that names no chain is refused.
        val chainId = reviewChainId(parsed) ?: throw Refusal(NO_NETWORK)
        EvmReviewResult.Ok(Build(parsed, sender, derivationPath, chainId).build().checked())
    } catch (refusal: Refusal) {
        EvmReviewResult.Refused(refusal.reason, refusal.rows)
    }

    /** One review as it is built. Its rows, warnings and coverage collect here until [build] returns the review. */
    private inner class Build(
        private val parsed: ParsedRequest,
        private val sender: String,
        private val derivationPath: String?,
        private val chainId: Long,
    ) {
        private val request = parsed.request
        private val network: NetworkEntry? = registry.network(chainId)
        private val networkName = network?.name ?: "Unknown network (chain id $chainId)"
        // A message, or typed data whose domain names no chain, that the request names no chain for is read on Ethereum mainnet, but it is
        // bound to no network, so the header names none.
        private val bindsNoChain = request.chainId == null && when (request.dataType) {
            DataType.PERSONAL_MESSAGE -> true
            DataType.TYPED_DATA -> (parsed.typed as? TypedDataParser.Result.Ok)?.data?.domainChainId == null
            else -> false
        }
        private val headerNetwork = if (bindsNoChain) {
            NetworkLabel(name = ANY_CHAIN_NAME, testnet = false, detail = null)
        } else {
            NetworkLabel(name = networkName, testnet = network?.testnet == true, detail = network?.let { "Chain ID $chainId" })
        }
        private val chain = ChainId("eip155:$chainId")
        private val header = ArrayList<ReviewRow>()
        private val sections = ArrayList<ReviewSection>()
        private val facts = ArrayList<ReviewRow>()
        private val warnings = ArrayList<Warning>()
        private val decoded = HashSet<FieldPath>()
        private val excluded = HashMap<FieldPath, String>()

        init {
            header += ReviewRow(FROM_LABEL, DisplayValue.Address(chain, sender), null)
            derivationPath?.let { path ->
                header += ReviewRow("Derivation Path", DisplayValue.Text(path), null)
                if (BIP44_ADDRESS_PATH.matches(path)) warnings += Warning(Danger.CAUTION, CODE_BIP44_ADDRESS, BIP44_ADDRESS_WARNING)
            }
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
            // An EIP-191 signature commits to no chain, so it is valid on every one, whichever chain the request names or none.
            val chainWarning = if (request.chainId == null) PERSONAL_MESSAGE_NO_CHAIN_WARNING else PERSONAL_MESSAGE_CHAIN_WARNING
            warnings += Warning(Danger.CAUTION, CODE_MESSAGE_CHAIN, chainWarning)
            return assemble(SIGN_MESSAGE_FUNCTION, listOf(SummaryPart.Text("Signs a message with this account.")), heading = "Request")
        }

        private fun typed(): Review {
            val typed = when (val result = parsed.typed) {
                is TypedDataParser.Result.Ok -> result
                is TypedDataParser.Result.Rejected -> throw Refusal(result.reason)
                null -> throw Refusal(CANNOT_BE_READ)
            }
            val typedData = typed.data
            // A domain may name another chain than the request's, or none. The signature is then shown under the chain it names, and it is never
            // refused for that. Only a denylist match refuses.
            val domainChain = typedData.domainChainId
            val offChain = offChainLabelOf(typedData.domain, typedData.primaryType)
            val verifyingContract = (typedData.domain.fields["verifyingContract"] as? TypedValue.BytesValue)
                ?.let { checksum(it.bytes.toHex()) }
            val denyChains = listOfNotNull(request.chainId, domainChain?.longValueExact()).distinct().ifEmpty { listOf(chainId) }
            val denied = denyChains.flatMap { registry.typedDenylistMatches(it, verifyingContract, typedData.primaryType) }
            denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { throw Refusal(it.reason) }
            denied.forEach { warnings += denylistWarning(it, field = null) }
            header += ReviewRow("Type", DisplayValue.Text(TYPED_DATA_TYPE), null)
            header += ReviewRow(DOMAIN_HASH_LABEL, DisplayValue.Text(hashText(typedData.domainSeparator()), mono = true), null)
            header += ReviewRow(MESSAGE_HASH_LABEL, DisplayValue.Text(hashText(typedData.messageHash()), mono = true), null)
            chainRow(domainChain, request.chainId)?.let { header += it }
            chainCaution(domainChain, request.chainId)?.let { warnings += it }
            if (offChain != null) {
                header += ReviewRow(offChain.title, DisplayValue.Text(offChainNote(offChain.label)), null)
                warnings += Warning(Danger.CAUTION, CODE_OFF_CHAIN, offChainCautionText(offChain.label), acknowledge = offChainAck(offChain.label))
                if (offChain.hashesAction) header += ReviewRow(HASHED_ACTION_LABEL, DisplayValue.Text(HASHED_ACTION_NOTE), null)
            }
            actionWarning(typedData.primaryType, typedData.message, offChain)?.let { warnings += it }
            val domainNetworkName = domainChain?.let { networkNameOf(it) } ?: networkName
            val part = typedPart(typedData, registry, chainId, chain, networkName, domainNetworkName = domainNetworkName)
            decoded += part.decoded
            warnings += part.warnings
            if (part.unknownToken) warnings += registryWarning(TOKEN_UNREGISTERED, field = null)
            sections += ReviewSection("Domain", part.domain)
            if (part.summary.isNotEmpty()) sections += ReviewSection("Summary", part.summary)
            sections += ReviewSection("Message", part.fields)
            // The members the message adds beside its typed fields. The signature does not cover them, so each name is labelled as unsigned.
            if (typed.unsigned.isNotEmpty()) {
                sections += ReviewSection(
                    "Not Signed",
                    typed.unsigned.map { (name, text) ->
                        ReviewRow(
                            "Unsigned: ${unsignedShown(name, UNSIGNED_NAME_BYTES)}",
                            DisplayValue.Text(unsignedShown(text, UNSIGNED_VALUE_BYTES)),
                            null,
                            notes = listOf(NOT_SIGNED_NOTE),
                        )
                    },
                )
            }
            val summary = listOf(SummaryPart.Text("Signs typed data of type ${typedData.primaryType}."))
            return assemble(typedData.primaryType, summary, heading = "Request")
        }

        /**
         * The row that names the chain a typed-data signature is for, or null when the request names that same chain. A domain that names no
         * chain says so, since its signature is then valid on any chain.
         */
        private fun chainRow(domainChain: BigInteger?, requestChain: Long?): ReviewRow? {
            if (domainChain == null) return ReviewRow(ANY_CHAIN_LABEL, DisplayValue.Text(ANY_CHAIN_NOTE), null)
            if (domainChain == requestChain?.let { BigInteger.valueOf(it) }) return null
            return ReviewRow(
                "Signature for Chain $domainChain (${networkNameOf(domainChain)})",
                DisplayValue.Text("The domain names chain ID $domainChain, and the request names ${requestedChain(requestChain)}."),
                null,
            )
        }

        /**
         * The CAUTION of a typed-data signature for a chain other than the request's, or for no chain: it is valid on the chain the domain names,
         * and the owner must check that chain before signing. Null when the request names the same chain as the domain.
         */
        private fun chainCaution(domainChain: BigInteger?, requestChain: Long?): Warning? {
            if (domainChain != null && domainChain == requestChain?.let { BigInteger.valueOf(it) }) return null
            val text = if (domainChain == null) {
                ANY_CHAIN_NOTE
            } else {
                "The signature is valid on chain ID $domainChain (${networkNameOf(domainChain)}), and the request names " +
                    "${requestedChain(requestChain)}. Check that chain before you sign."
            }
            return Warning(Danger.CAUTION, CODE_TYPED_CHAIN, text, acknowledge = ACK_TYPED_CHAIN)
        }

        private fun networkNameOf(chain: BigInteger): String = registry.network(chain.toLong())?.name ?: UNKNOWN_NETWORK

        private fun requestedChain(requestChain: Long?): String = requestChain?.let { "chain ID $it" } ?: "no chain ID"

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
            // The refusal keeps the call's rows built so far, so a call Lensly cannot decode still shows its raw data.
            denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { throw Refusal(it.reason, sections.flatMap { s -> s.rows }) }
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
            return "Native Transfer" to listOf(sendOf(tx.value, network?.nativeDecimals, network?.nativeSymbol, sender, to))
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
                    sections += ReviewSection("Arguments", rowsOf(approvedDetails(gap, calldata), "evm.data", 0))
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
                denied.firstOrNull { it.severity == DenylistSeverity.BLOCK }?.let { throw Refusal(it.reason, rows.toList()) }
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
                                flags + approvedDetails(gap, inner.calldata),
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
            val details = undecodedRows(contract, value, calldata, proxy)
            if (entry == null) {
                if (unknownCalls == UnknownCallPolicy.REFUSE) throw Refusal(UNKNOWN_CALL_REFUSAL, details)
                return Gap.Unknown(calldata.copyOfRange(0, minOf(SELECTOR_BYTES, calldata.size)).toHex())
            }
            if (entry.upgradeable && proxyPolicy == ProxyPolicy.BIND_TO_IMPLEMENTATION) throw Refusal(BOUND_PROXY_REFUSAL, details)
            if (entry.upgradeable && proxyPolicy == ProxyPolicy.REFUSE_ALL_PROXIES) throw Refusal(ANY_PROXY_REFUSAL, details)
            val arguments = when (val result = decodeCall(calldata, entry.params)) {
                is CallDecode.Ok -> result.params
                is CallDecode.Refused -> throw Refusal(result.reason, details)
            }
            // The decoder must answer with exactly the params the entry declares, in their order.
            if (arguments.map { it.param } != entry.params) throw Refusal(CANNOT_BE_READ, details)
            // The labels are shown only when the decoded values are exactly the arguments that get signed.
            if (AbiDecoder.encode(arguments)?.contentEquals(argumentsOf(calldata)) != true) throw Refusal(CANNOT_BE_READ, details)
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
         * The rows of one call's arguments: the approved meaning first, then the arguments, then the selector, the signed calldata in full, and
         * each word after the selector. The arguments preview a bytes value, so the calldata row is what shows every byte of it. The proxy
         * binding comes last. The call's value is not repeated here: a top-level call shows the header's amount, and a batch call shows its
         * own value in the flags its shape carries.
         */
        private fun approvedDetails(gap: Gap.Approved, calldata: ByteArray): List<ParamRow> = buildList {
            add(ParamRow("Approved Meaning", DisplayValue.Text(gap.meaning), null))
            addAll(gap.params)
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
                    sentence = listOf(sendOf(call.value, token?.decimals, token?.symbol, sender, checksum(call.to))),
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

        /**
         * What a refusal of an undecodable call shows, as review rows: its contract, network, value and selector, the implementation a bound
         * proxy is bound to, and then the calldata after the selector, one 32-byte word per row under a "Calldata" heading.
         */
        private fun undecodedRows(contract: String, value: BigInteger, calldata: ByteArray, proxy: ProxyBinding?): List<ReviewRow> = buildList {
            val selector = calldata.copyOfRange(0, minOf(SELECTOR_BYTES, calldata.size)).toHex()
            add(ReviewRow("Contract", DisplayValue.Address(chain, checksum(contract)), null))
            add(ReviewRow("Network", DisplayValue.Text(networkName), null, notes = listOf("Chain $chainId")))
            add(ReviewRow("Value", DisplayValue.Text(nativeAmount(value), mono = true), null))
            add(ReviewRow("Selector", DisplayValue.Text("0x$selector", mono = true), null))
            proxy?.implementationAddress?.let { add(ReviewRow("Approved Implementation", DisplayValue.Address(chain, it), null)) }
            proxy?.implementationCodeHash?.let {
                add(ReviewRow("Approved Code Hash", DisplayValue.Text("0x" + groupedHex(it.removePrefix("0x")), mono = true), null))
            }
            add(ReviewRow("Calldata", null, null))
            wordsOf(calldata).forEachIndexed { index, word ->
                add(ReviewRow("WORD ${index + 1}", DisplayValue.Text("0x" + groupedHex(word.toHex()), mono = true), null, depth = 1))
            }
        }

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
                network = headerNetwork,
                summary = summary,
                facts = facts,
                sections = sections,
                warnings = warnings,
                coverage = Coverage(decoded = decoded, excluded = excluded),
            )
        }
    }

    /** A refusal raised while a review is built. It carries the reason the request is refused, and the rows of the call that caused it, if any. */
    private class Refusal(val reason: String, val rows: List<ReviewRow> = emptyList()) : RuntimeException(reason, null, false, false)
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

/** The rows of a call Lensly cannot read: its selector, its calldata in full, and the hash of that calldata for comparison with Lumio. */
private fun unknownParams(calldata: ByteArray, selector: String): List<ParamRow> = listOf(
    ParamRow("Selector", DisplayValue.Text("0x$selector"), "selector"),
    calldataRow(calldata),
    ParamRow(CALLDATA_HASH_LABEL, DisplayValue.Text(hashText(Hash.sha3(calldata)), mono = true), null),
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

/**
 * An unsigned name or text as the review shows it. A line break or a tab is an escape, so it cannot start a row, and the rest is shown as
 * [printableOrHex] shows it. A text longer than [limit] bytes is cut between characters, and the bytes left out are counted.
 */
private fun unsignedShown(text: String, limit: Int): String {
    val shown = printableOrHex(text).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    val bytes = shown.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= limit) return shown
    var cut = limit
    while ((bytes[cut].toInt() and 0xC0) == 0x80) cut-- // a continuation byte: the cut would split a character
    return String(bytes, 0, cut, StandardCharsets.UTF_8) + "... ${bytes.size - cut} more bytes"
}

/** The note under the header row of an off-chain signature: what its domain matches, and what Lensly cannot tell. */
private fun offChainNote(label: String): String =
    "The domain matches $label's published message format. Lensly cannot tell what the requester does with the signature."

/** The CAUTION of an off-chain signature: it is for the product, and it can act for the account there. */
private fun offChainCautionText(label: String): String =
    "This signature is for $label. It can authorize actions in your $label account. Check the message before you sign."

/** The tick of an off-chain signature for [label]. */
private fun offChainAck(label: String): String = "I understand this signature goes to $label, and Lensly cannot tell what it does."

/**
 * The DANGER of a message that lets a key act for the account or moves its funds, or null for any other primary type. An agent approval is
 * an ApproveAgent on any domain, or a message that the labelled product names as its approval and that carries the agent.
 */
private fun actionWarning(primaryType: String, message: TypedValue.StructValue, offChain: OffChainDomain?): Warning? = when {
    offChain?.isAgentApproval(primaryType, message) == true || primaryType.substringAfterLast(':') == "ApproveAgent" ->
        Warning(Danger.DANGER, CODE_TYPED_ACTION, APPROVE_AGENT_TEXT, acknowledge = ACK_APPROVE_AGENT)
    primaryType.substringAfterLast(':') in setOf("Withdraw", "withdraw3") ->
        Warning(Danger.DANGER, CODE_TYPED_ACTION, WITHDRAW_TEXT, acknowledge = ACK_WITHDRAW)
    else -> null
}

private fun amountText(raw: BigInteger, token: TokenEntry?): String =
    if (token == null) "$raw $UNKNOWN_UNIT (unknown token, decimals unknown)" else "$raw (${Amounts.format(raw, token.decimals)} ${token.symbol})"

private fun displayAmount(raw: BigInteger, token: TokenEntry?): String =
    if (token == null) "$raw $UNKNOWN_UNIT" else "${Amounts.format(raw, token.decimals)} ${token.symbol}"

/**
 * The send of [raw] base units from [from] to [to]. Its amount is in the units of [unit] with [decimals] places, and without decimals, or
 * for a coin or token Lensly does not know, it is in base units.
 */
private fun sendOf(raw: BigInteger, decimals: Int?, unit: String?, from: String, to: String): SummaryPart.Send =
    if (decimals == null || unit == null) {
        SummaryPart.Send(raw.toString(), UNKNOWN_UNIT, from, to)
    } else {
        SummaryPart.Send(Amounts.format(raw, decimals), unit, from, to)
    }

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

/** Hex in groups of eight digits, as a calldata word or a hash is read on a refusal. */
private fun groupedHex(hex: String): String = hex.chunked(8).joinToString(" ")

/** A hash as the review shows it: 0x and every byte in hex, never shortened. The signer compares its recomputed hashes in this form. */
internal fun hashText(hash: ByteArray): String = "0x" + hash.toHex()

private fun messageValue(bytes: ByteArray): DisplayValue =
    printableMessage(bytes)?.let { DisplayValue.Message(it) } ?: DisplayValue.Bytes(bytes)

/** The message as UTF-8 text, when every character is one a reader can see. Otherwise null. */
private fun printableMessage(bytes: ByteArray): String? =
    utf8OrNull(bytes)?.takeIf { text -> text.codePoints().allMatch(::isPrintableCodePoint) }

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

/** The label of the row that names the account a request is signed for. The signer checks its key against this row. */
internal const val FROM_LABEL = "From"

/** The labels of the hashes a typed-data review shows. The signer recomputes the first two and checks them against these rows. */
internal const val DOMAIN_HASH_LABEL = "Domain Hash"
internal const val MESSAGE_HASH_LABEL = "Message Hash"

/**
 * The label of the hash of an undecoded call's calldata. It is not the Data Hash of a record, which is the SHA-256 of the whole payload, so the
 * two never share a label.
 */
internal const val CALLDATA_HASH_LABEL = "Calldata Hash (Keccak-256)"

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
private const val CODE_MESSAGE_CHAIN = "evm.personal-message.chain"
private const val CODE_BIP44_ADDRESS = "evm.bip44-address"

private const val SIGN_DATA_TOO_LARGE = "The data to sign is larger than 64 KiB"
internal const val NO_NETWORK = "The request does not name a network"
private const val UNREADABLE_ORIGIN = "The request names an origin with a character Lensly cannot show."
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
private const val PERSONAL_MESSAGE_CHAIN_WARNING = "This signature is valid on every EVM chain, not only the chain the request names."
private const val PERSONAL_MESSAGE_NO_CHAIN_WARNING = "This message names no chain, so its signature is not bound to a network. " +
    "It is valid on every EVM chain."

/** The path of a BIP44 address key, m/44'/60'/0'/0/N (Rabby derives its keys so). N of 0 is account 0's own key, so it is not one. */
private val BIP44_ADDRESS_PATH = Regex("""m/44'/60'/0'/0/[1-9][0-9]*""")
private const val BIP44_ADDRESS_WARNING = "This key is a BIP44 address key, not one of this wallet's accounts. Check its address and path before you sign."
private const val PERSONAL_MESSAGE_TYPE = "Personal Message"
private const val TYPED_DATA_TYPE = "Typed data (EIP-712)"

/** The name a typed-data domain's chain has when it is not in Lensly's network registry. */
private const val UNKNOWN_NETWORK = "Unknown Network"

/** The title and note of a typed-data signature whose domain names no chain. */
private const val ANY_CHAIN_LABEL = "Signature for Any Chain"
private const val ANY_CHAIN_NAME = "Any Chain"
private const val ANY_CHAIN_NOTE = "The domain names no chain ID, so this signature is valid on any chain."

/** The note under a member that a message adds beside its typed fields. The signature does not cover it. */
private const val NOT_SIGNED_NOTE = "Not signed. The signature covers only the typed fields above."

/** The most bytes of an unsigned member's name and of its text that the review shows. The rest is counted, not shown. */
private const val UNSIGNED_NAME_BYTES = 128
private const val UNSIGNED_VALUE_BYTES = 512

/** The codes and ticks of a typed-data CAUTION or DANGER: its chain, its off-chain product and its action. */
private const val CODE_TYPED_CHAIN = "evm.typed.chain"
private const val CODE_OFF_CHAIN = "evm.typed.off-chain"
private const val CODE_TYPED_ACTION = "evm.typed.action"
private const val ACK_TYPED_CHAIN = "I understand this signature may be valid on another chain."
private const val ACK_APPROVE_AGENT = "I understand this lets another key act for this account."
private const val ACK_WITHDRAW = "I understand this withdraws funds from this account."
private const val APPROVE_AGENT_TEXT = "This approves another key to act for this account. Check the agent address and name before you sign."
private const val WITHDRAW_TEXT = "This withdraws funds from this account. Check the destination and amount before you sign."

/** The row that says a message carries a hash of its action, which Lensly cannot read. */
private const val HASHED_ACTION_LABEL = "Order Details Are Hashed by Hyperliquid and Cannot Be Shown"
private const val HASHED_ACTION_NOTE = "The connection ID is a hash of the action. Lensly cannot read the action, so it cannot show what the action does."
private const val L2_FEE_NOTE = "L1 data fee is not computable offline; total may be higher"
private const val DEADLINE_LABEL = "deadline (uint256)"

/** The label of the origin a request names. The request sets it, so the review does not vouch for it. */
private const val CLAIMED_ORIGIN_LABEL = "Claimed by Request (Not Verified)"

private const val GWEI_DECIMALS = 9
private const val ASSUMED_DECIMALS = 18
private const val SELECTOR_BYTES = 4
private const val WORD_BYTES = 32
private val AMOUNT_PARAMS = setOf("amount", "value", "wad")
private val TOO_DEEP_REFUSAL = "The calls nest deeper than ${ParserLimits.MAX_NESTED_DEPTH} levels, so Lensly cannot show every call."
private val TOO_MANY_CALLS_REFUSAL = "The transaction holds more than ${ParserLimits.MAX_NESTED_CALLS} calls, so Lensly cannot show every call."
