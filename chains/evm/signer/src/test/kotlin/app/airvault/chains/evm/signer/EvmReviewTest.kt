package app.airvault.chains.evm.signer

import app.airvault.abi.AbiDecoder
import app.airvault.abi.WhitelistParam
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.FieldPath
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.checked
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import app.lensly.core.registry.DenylistCondition
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
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

private const val USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val USDC_LOWER = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
private const val SPENDER = "0x1111111111111111111111111111111111111111"
private const val NFT = "0x2222222222222222222222222222222222222222"
private const val MULTICALL3 = "0xcA11bde05977b3631167028862bE2a173976CA11"
private const val SENDER = "0x3333333333333333333333333333333333333333"
private const val APPROVE_SELECTOR = "0x095ea7b3"
private const val UNLIMITED_WARNING = "Unlimited approval: this contract can spend all your USDC"
private const val SET_APPROVAL_WARNING = "Grants control of ALL your NFTs in this collection"
private const val UNKNOWN_WARNING =
    "Unknown contract call. Lensly does not read this call, so it cannot say what it does. Check the contract and the calldata before you sign."
private const val ACK_UNLIMITED = "I understand this approval is unlimited."
private const val ACK_ALL_NFTS = "I understand this grants control of all NFTs in this collection."
private const val ACK_UNKNOWN = "I understand Lensly cannot say what this call does."
private const val ACK_DENYLIST = "I understand this request matches a known-dangerous request."
private const val ACK_WHITELIST = "I have read what this call is approved to do."
private const val ACK_UPGRADE = "I accept that this proxy's code cannot be checked offline."
private const val IMPLEMENTATION = "0x4444444444444444444444444444444444444444"

private val MAX_UINT256: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)
private val PATH = listOf(
    PathStep(44, true),
    PathStep(60, true),
    PathStep(0, true),
    PathStep(0, false),
    PathStep(0, false),
)

/** Monad testnet (10143), as the debug catalog names it, and Ethereum with USDC. */
private val REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(10143, "Monad Testnet", "MON", 18, FeeModel.L1, "MonadVision", testnet = true),
        NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", testnet = false),
    ),
    tokens = listOf(TokenEntry(1, USDC_LOWER, "USDC", "USD Coin", 6)),
)

private fun builder(
    registry: Registry = REGISTRY,
    unknownCalls: UnknownCallPolicy = UnknownCallPolicy.REFUSE,
    proxyPolicy: ProxyPolicy = ProxyPolicy.DEFAULT,
) = EvmReviewBuilder(registry, { calldata, params -> AbiDecoder.decode(calldata, params) }, proxyPolicy, unknownCalls)

/** The parsed request of a legacy transaction to [to] with [data], as a wallet sends it. */
private fun txParsed(to: String, data: ByteArray, value: BigInteger = BigInteger.ZERO, chainId: Long = 1): ParsedRequest {
    val tx = UnsignedTx(
        type = TxType.LEGACY_EIP155,
        chainId = BigInteger.valueOf(chainId),
        nonce = BigInteger.valueOf(7),
        to = to,
        value = value,
        data = "0x" + data.toHex(),
        gasLimit = BigInteger.valueOf(60_000),
        gasPrice = BigInteger.valueOf(2_000_000_000),
        maxPriorityFeePerGas = null,
        maxFeePerGas = null,
    )
    val request = EthSignRequest(
        requestId = null,
        signData = tx.encode()!!,
        dataType = DataType.TRANSACTION,
        chainId = chainId,
        derivationPath = PATH,
        sourceFingerprint = null,
        address = null,
        origin = "dapp.example",
    )
    return EvmRequestParser.parsedOf(request)
}

/** The review of [parsed], which must show every field it decodes: the coverage check holds for every review this file builds. */
private fun reviewOf(parsed: ParsedRequest, builder: EvmReviewBuilder = builder()): Review {
    val review = assertIs<EvmReviewResult.Ok>(builder.review(parsed, SENDER)).review
    assertTrue(review.unaccounted().isEmpty(), "every decoded field is shown: ${review.unaccounted()}")
    return review
}

private fun refusalOf(parsed: ParsedRequest, builder: EvmReviewBuilder = builder()): EvmReviewResult.Refused =
    assertIs<EvmReviewResult.Refused>(builder.review(parsed, SENDER))

class EvmReviewTest {
    @Test
    fun aNativeTransferOnMonadTestnetIsLabelledWithItsRegistryNetwork() {
        val review = reviewOf(txParsed(SPENDER, ByteArray(0), value = BigInteger("10000000000000000"), chainId = 10143))

        assertEquals(ChainId("eip155:10143"), review.chain)
        assertEquals(NetworkLabel("Monad Testnet", testnet = true, detail = "Chain ID 10143"), review.network)
        assertEquals("Send 0.01 MON from $SENDER to $SPENDER?", review.summary.text())
        assertTrue(review.warnings.isEmpty())
    }

    @Test
    fun anUnlimitedApprovalIsDangerAndNamesItsCheck() {
        val review = reviewOf(txParsed(USDC, ContractCall.Approve(SPENDER, MAX_UINT256).encode()!!))

        val warning = review.warnings.single { it.code == "evm.unlimited-approval" }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(UNLIMITED_WARNING, warning.message)
        assertEquals(FieldPath("evm.data.value"), warning.field)
        assertEquals(ACK_UNLIMITED, warning.acknowledge)
    }

    @Test
    fun aLimitedApprovalIsNotWarnedAgainst() {
        val review = reviewOf(txParsed(USDC, ContractCall.Approve(SPENDER, BigInteger("1000000")).encode()!!))

        assertTrue(review.warnings.isEmpty())
    }

    @Test
    fun aSetApprovalForAllGrantIsDangerAndNamesItsCheck() {
        val review = reviewOf(txParsed(NFT, ContractCall.SetApprovalForAll(SPENDER, true).encode()!!))

        val grant = review.warnings.single { it.code == "evm.approval-for-all" }
        assertEquals(Danger.DANGER, grant.level)
        assertEquals(SET_APPROVAL_WARNING, grant.message)
        assertEquals(ACK_ALL_NFTS, grant.acknowledge)
        // The NFT contract is outside the token registry, so its check is named too.
        assertTrue(review.warnings.any { it.code == "evm.registry" && it.acknowledge != null })
    }

    @Test
    fun aBlockDenylistMatchRefusesTheRequestWithItsReason() {
        val blocked = REGISTRY.withDenylist(
            listOf(DenylistEntry(chainId = 1, contract = NFT, severity = DenylistSeverity.BLOCK, reason = "Blocked collection.")),
        )

        val refused = refusalOf(txParsed(NFT, ContractCall.SetApprovalForAll(SPENDER, true).encode()!!), builder(blocked))

        assertEquals("Blocked collection.", refused.reason)
    }

    /** A BLOCK match on a call Lensly cannot decode, under the Warn policy, keeps the call's rows: the refusal shows its raw data. */
    @Test
    fun aBlockDenylistMatchOnAnUndecodedCallKeepsItsRows() {
        val blocked = REGISTRY.withDenylist(
            listOf(DenylistEntry(chainId = 1, contract = NFT, severity = DenylistSeverity.BLOCK, reason = "Blocked collection.")),
        )
        val calldata = Numeric.hexStringToByteArray("0xdeadbeef") + ByteArray(32) { 1 }

        val refused = refusalOf(txParsed(NFT, calldata), builder(blocked, unknownCalls = UnknownCallPolicy.WARN))

        assertEquals("Blocked collection.", refused.reason)
        assertEquals(listOf("Selector", "Calldata", "Calldata Hash (Keccak-256)"), refused.rows.map { it.label })
        assertEquals("0xdeadbeef", refused.rows.single { it.label == "Selector" }.value.text())
    }

    @Test
    fun aWarnDenylistMatchIsDangerAndNeedsItsCheck() {
        val warned = REGISTRY.withDenylist(
            listOf(
                DenylistEntry(
                    chainId = 1,
                    contract = USDC_LOWER,
                    selector = APPROVE_SELECTOR,
                    condition = DenylistCondition.UNLIMITED,
                    severity = DenylistSeverity.WARN,
                    reason = "A known drainer approval.",
                ),
            ),
        )

        val review = reviewOf(txParsed(USDC, ContractCall.Approve(SPENDER, MAX_UINT256).encode()!!), builder(warned))

        val denylist = review.warnings.single { it.code == "evm.denylist" }
        assertEquals(Danger.DANGER, denylist.level)
        assertEquals("A known drainer approval.", denylist.message)
        assertEquals(ACK_DENYLIST, denylist.acknowledge)
    }

    @Test
    fun aDecodedFieldNoRowShowsBlocksTheReview() {
        val unshown = Review(
            title = "Native Transfer",
            chain = ChainId("eip155:1"),
            network = NetworkLabel("Ethereum", testnet = false, detail = "Chain ID 1"),
            summary = emptyList(),
            facts = emptyList(),
            sections = emptyList(),
            warnings = emptyList(),
            coverage = Coverage(decoded = setOf(FieldPath("evm.value")), excluded = emptyMap()),
        )

        val checked = unshown.checked()

        assertEquals(Danger.BLOCK, checked.warnings.single().level)
        assertEquals("coverage", checked.warnings.single().code)
    }

    @Test
    fun aMulticallListsItsCallsAndWarnsOfTheUnlimitedApprovalInsideIt() {
        val approve = ContractCall.Approve(SPENDER, MAX_UINT256).encode()!!
        val call = InnerCall(
            target = USDC,
            allowFailure = false,
            value = null,
            calldata = approve,
            call = ContractCall.decode(approve),
            nested = null,
        )
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!

        val review = reviewOf(txParsed(MULTICALL3, batch))

        assertEquals("aggregate3", review.title)
        assertNotNull(review.sections.singleOrNull { it.heading == "Calls" })
        val unlimited = review.warnings.single { it.code == "evm.unlimited-approval" }
        assertEquals(FieldPath("evm.data.call[0].value"), unlimited.field)
        assertEquals(UNLIMITED_WARNING, unlimited.message)
    }

    @Test
    fun anUnknownCallIsRefusedWithItsRawDataUnderTheRefusePolicy() {
        val calldata = Numeric.hexStringToByteArray("0xdeadbeef") + ByteArray(32) { 1 }

        val refused = refusalOf(txParsed(NFT, calldata))

        assertEquals("Unknown Contract Call", refused.reason)
        assertEquals("0xdeadbeef", refused.rows.single { it.label == "Selector" }.value.text())
        assertNull(refused.rows.find { it.label == "Approved Implementation" })
        // The calldata after the selector is one word row, indented under the Calldata heading.
        assertEquals(1, refused.rows.count { it.depth == 1 })
    }

    @Test
    fun anUnknownCallIsDangerUnderTheWarnPolicyAndNamesItsCheck() {
        val calldata = Numeric.hexStringToByteArray("0xdeadbeef") + ByteArray(32) { 1 }

        val review = reviewOf(txParsed(NFT, calldata), builder(unknownCalls = UnknownCallPolicy.WARN))

        val unknown = review.warnings.single { it.code == "evm.unknown-call" }
        assertEquals(Danger.DANGER, unknown.level)
        assertEquals(UNKNOWN_WARNING, unknown.message)
        assertEquals(ACK_UNKNOWN, unknown.acknowledge)
        assertEquals(
            listOf("Selector", "Calldata", "Calldata Hash (Keccak-256)"),
            review.sections.single { it.heading == "Arguments" }.rows.map { it.label },
        )
    }

    /** The Calldata Hash of an undecoded call is the keccak256 of its whole calldata, shown in full, for comparison with Lumio. */
    @Test
    fun anUnknownCallShowsTheHashOfItsCalldata() {
        val calldata = Numeric.hexStringToByteArray("0xdeadbeef") + ByteArray(32) { 1 }

        val review = reviewOf(txParsed(NFT, calldata), builder(unknownCalls = UnknownCallPolicy.WARN))

        val hash = review.sections.single { it.heading == "Arguments" }.rows.single { it.label == "Calldata Hash (Keccak-256)" }
        assertEquals("0x" + Hash.sha3(calldata).toHex(), hash.value.text())
    }

    @Test
    fun anUnverifiedOriginIsLabelledAsAClaimThatTheReviewDoesNotVouchFor() {
        val review = reviewOf(txParsed(SPENDER, ByteArray(0)))

        val header = review.sections.first().rows
        assertEquals("dapp.example", header.single { it.label == "Claimed by Request (Not Verified)" }.value.text())
        assertTrue(header.none { it.label == "Origin" })
    }

    @Test
    fun aWhitelistedCallShowsItsApprovedMeaningAndItsDecodedArguments() {
        val review = reviewOf(txParsed(NFT, fooCalldata()), builder(REGISTRY.withWhitelist(listOf(fooEntry()))))

        assertEquals("foo", review.title)
        val arguments = review.sections.single { it.heading == "Arguments" }.rows
        assertEquals("Moves the amount to the recipient.", arguments.single { it.label == "Approved Meaning" }.value.text())
        assertEquals("42", arguments.single { it.label == "amount (uint256)" }.value.text())
        val approved = review.warnings.single { it.code == "evm.whitelisted-call" }
        assertEquals(Danger.CAUTION, approved.level)
        assertEquals(ACK_WHITELIST, approved.acknowledge)
    }

    @Test
    fun anApprovedCallShowsItsSelectorAndEveryWordAfterItWithTheAmountInTheHeader() {
        val calldata = fooCalldata()

        val registry = REGISTRY.withWhitelist(listOf(fooEntry()))
        val review = reviewOf(txParsed(NFT, calldata, value = BigInteger("1000000000000000")), builder(registry))

        // The header already shows the amount the call sends, so the arguments do not repeat it.
        assertEquals("0.001 ETH", review.sections.first().rows.single { it.label == "Amount" }.value.text())
        val arguments = review.sections.single { it.heading == "Arguments" }.rows
        assertTrue(arguments.none { it.label == "Value" })
        assertEquals("0x" + calldata.copyOfRange(0, 4).toHex(), arguments.single { it.label == "Selector" }.value.text())
        assertEquals("0x" + calldata.copyOfRange(4, 36).toHex(), arguments.single { it.label == "Word 1" }.value.text())
        assertEquals("0x" + calldata.copyOfRange(36, 68).toHex(), arguments.single { it.label == "Word 2" }.value.text())
    }

    @Test
    fun anApprovedCallInsideAMulticallShowsItsOwnValueOnceInItsFlags() {
        val call = InnerCall(
            target = NFT,
            allowFailure = false,
            value = BigInteger("1000000000000000"),
            calldata = fooCalldata(),
            call = null,
            nested = null,
        )
        val batch = encodeMulticall(MulticallShape.AGGREGATE3_VALUE, deadline = null, calls = listOf(call))!!

        val review = reviewOf(
            txParsed(MULTICALL3, batch, value = BigInteger("2000000000000000")),
            builder(REGISTRY.withWhitelist(listOf(fooEntry()))),
        )

        // The batch's flags carry the call's value, and the header carries the transaction's amount. No row repeats the call's value.
        assertEquals("0.002 ETH", review.sections.first().rows.single { it.label == "Amount" }.value.text())
        val rows = review.sections.single { it.heading == "Calls" }.rows
        assertEquals("0.001 ETH", rows.single { it.label == "value (uint256)" }.value.text())
        assertTrue(rows.none { it.label == "Value" })
    }

    @Test
    fun anApprovedCallThatSendsNoNativeValueShowsNoValueRow() {
        val review = reviewOf(txParsed(NFT, fooCalldata()), builder(REGISTRY.withWhitelist(listOf(fooEntry()))))

        assertTrue(review.sections.single { it.heading == "Arguments" }.rows.none { it.label == "Value" })
    }

    @Test
    fun anApprovedCallInsideAMulticallShowsItsSelectorAndWords() {
        val inner = fooCalldata()
        val call = InnerCall(target = NFT, allowFailure = false, value = null, calldata = inner, call = null, nested = null)
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!

        val review = reviewOf(txParsed(MULTICALL3, batch), builder(REGISTRY.withWhitelist(listOf(fooEntry()))))

        val rows = review.sections.single { it.heading == "Calls" }.rows
        assertEquals("0x" + inner.copyOfRange(0, 4).toHex(), rows.single { it.label == "Selector" }.value.text())
        assertEquals("0x" + inner.copyOfRange(4, 36).toHex(), rows.single { it.label == "Word 1" }.value.text())
    }

    @Test
    fun anApprovedCallShowsEveryByteOfItsBytesArgument() {
        val payload = ByteArray(40) { (it + 1).toByte() }
        val calldata = bytesCall(bytesEntry().selector, payload)

        val review = reviewOf(txParsed(NFT, calldata), builder(REGISTRY.withWhitelist(listOf(bytesEntry()))))

        val arguments = review.sections.single { it.heading == "Arguments" }.rows
        assertEquals("0x" + calldata.toHex(), arguments.single { it.label == "Calldata" }.value.text())
        // The bytes argument keeps its preview beside the full calldata.
        assertEquals("40 bytes: 0x" + payload.toHex().take(64) + "…", arguments.single { it.label == "data (bytes)" }.value.text())
    }

    @Test
    fun anApprovedCallInsideAMulticallShowsEveryByteOfItsBytesArgument() {
        val inner = bytesCall(bytesEntry().selector, ByteArray(40) { (it + 1).toByte() })
        val call = InnerCall(target = NFT, allowFailure = false, value = null, calldata = inner, call = null, nested = null)
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!

        val review = reviewOf(txParsed(MULTICALL3, batch), builder(REGISTRY.withWhitelist(listOf(bytesEntry()))))

        val rows = review.sections.single { it.heading == "Calls" }.rows
        assertEquals("0x" + inner.toHex(), rows.single { it.label == "Calldata" }.value.text())
    }

    @Test
    fun aPersonalMessageShowsItsTextWithACautionAndNoFee() {
        val request = EthSignRequest(
            requestId = null,
            signData = "hello".toByteArray(),
            dataType = DataType.PERSONAL_MESSAGE,
            chainId = 10143,
            derivationPath = PATH,
            sourceFingerprint = null,
            address = null,
            origin = "dapp.example",
        )

        val review = reviewOf(EvmRequestParser.parsedOf(request))

        assertEquals("Sign Message", review.title)
        val message = review.sections.single { it.heading == "Message" }.rows
        assertEquals("hello", message.single { it.label == "Message" }.value.text())
        assertEquals("5 characters, 5 bytes", message.single { it.label == "Size" }.value.text())
        assertTrue(review.facts.isEmpty())
        // The request names a chain, so the review also says the signature is valid on every chain.
        assertEquals(listOf("evm.personal-message", "evm.personal-message.chain"), review.warnings.map { it.code })
        assertEquals(listOf(Danger.CAUTION, Danger.CAUTION), review.warnings.map { it.level })
    }

    @Test
    fun aPersonalMessageThatNamesNoChainShowsNoNetworkAndSaysItIsNotBoundToOne() {
        val request = EthSignRequest(
            requestId = null,
            signData = "hello".toByteArray(),
            dataType = DataType.PERSONAL_MESSAGE,
            chainId = null,
            derivationPath = PATH,
            sourceFingerprint = null,
            address = null,
            origin = "dapp.example",
        )

        val review = reviewOf(EvmRequestParser.parsedOf(request))

        assertEquals("Sign Message", review.title)
        assertEquals(ChainId("eip155:1"), review.chain)
        assertEquals(NetworkLabel("Any Chain", testnet = false, detail = null), review.network)
        assertEquals("hello", review.sections.single { it.heading == "Message" }.rows.single { it.label == "Message" }.value.text())
        val chainWarning = review.warnings.single { it.code == "evm.personal-message.chain" }
        assertEquals(Danger.CAUTION, chainWarning.level)
        assertEquals(
            "This message names no chain, so its signature is not bound to a network. It is valid on every EVM chain.",
            chainWarning.message,
        )
    }

    @Test
    fun typedDataWhoseDomainAndRequestNameNoChainShowsAnyChainNotEthereum() {
        val json = """{"types":{"Note":[{"name":"text","type":"string"}],"EIP712Domain":[{"name":"name","type":"string"}]},""" +
            """"primaryType":"Note","domain":{"name":"Lensly"},"message":{"text":"hi"}}"""
        val request = EthSignRequest(
            requestId = null,
            signData = json.toByteArray(Charsets.UTF_8),
            dataType = DataType.TYPED_DATA,
            chainId = null,
            derivationPath = PATH,
            sourceFingerprint = null,
            address = null,
            origin = "dapp.example",
        )

        val review = reviewOf(EvmRequestParser.parsedOf(request))

        assertEquals(NetworkLabel("Any Chain", testnet = false, detail = null), review.network)
        assertTrue(review.sections.single { it.heading == "Request" }.rows.any { it.label == "Signature for Any Chain" })
    }

    @Test
    fun aTransactionThatNamesNoChainIsStillRefused() {
        val request = EthSignRequest(
            requestId = null,
            signData = "hello".toByteArray(),
            dataType = DataType.TRANSACTION,
            chainId = null,
            derivationPath = PATH,
            sourceFingerprint = null,
            address = null,
            origin = "dapp.example",
        )

        assertEquals("The request does not name a network", refusalOf(EvmRequestParser.parsedOf(request)).reason)
    }

    @Test
    fun aTransferOfAKnownTokenIsASendOfItsAmountToItsChecksummedRecipient() {
        // The calldata carries the recipient in lower case. The title names it checksummed, as the rest of the review shows it.
        val recipient = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        val review = reviewOf(txParsed(USDC, ContractCall.Transfer(recipient.lowercase(), BigInteger.valueOf(1_500_000)).encode()!!))

        assertEquals("transfer", review.title)
        assertEquals("Send 1.5 USDC from $SENDER to $recipient?", review.summary.text())
    }

    @Test
    fun aTransferFromAKnownTokenShowsItsDecimalsAndBothParties() {
        val review = reviewOf(txParsed(USDC, ContractCall.TransferFrom(SENDER, SPENDER, BigInteger.valueOf(1_500_000)).encode()!!))

        assertEquals("transferFrom", review.title)
        assertEquals("Moves 1.5 USDC from $SENDER to $SPENDER.", review.summary.text())
        assertEquals(
            listOf("from" to SENDER, "to" to SPENDER, "value" to "1500000 (1.5 USDC)"),
            review.sections.single { it.heading == "Arguments" }.rows.map { it.label to it.value.text() },
        )
        assertTrue(review.warnings.isEmpty())
    }

    @Test
    fun aTransferFromAnUnregisteredContractIsReadAsATokenOrNftAndNamesItsCheck() {
        val review = reviewOf(txParsed(NFT, ContractCall.TransferFrom(SENDER, SPENDER, BigInteger.valueOf(7)).encode()!!))

        // The third argument may be an amount or a token id, so it is shown as a number and not as an amount.
        assertEquals("transferFrom (token or NFT)", review.title)
        assertEquals("Moves a token or NFT from $SENDER to $SPENDER.", review.summary.text())
        assertEquals(
            listOf("from" to SENDER, "to" to SPENDER, "amount or tokenId" to "7"),
            review.sections.single { it.heading == "Arguments" }.rows.map { it.label to it.value.text() },
        )
        assertNotNull(review.warnings.single { it.code == "evm.registry" }.acknowledge)
    }

    @Test
    fun theUnlimitedThresholdIsTwoToTheOneSixtyMinusOneForApprovalsAndAllowanceIncreases() {
        val threshold = BigInteger.ONE.shiftLeft(160).subtract(BigInteger.ONE)
        fun unlimitedWarned(call: ContractCall): Boolean =
            reviewOf(txParsed(USDC, call.encode()!!)).warnings.any { it.code == "evm.unlimited-approval" }

        assertTrue(unlimitedWarned(ContractCall.Approve(SPENDER, threshold)))
        assertFalse(unlimitedWarned(ContractCall.Approve(SPENDER, threshold.subtract(BigInteger.ONE))))
        assertTrue(unlimitedWarned(ContractCall.IncreaseAllowance(SPENDER, threshold)))
        assertFalse(unlimitedWarned(ContractCall.IncreaseAllowance(SPENDER, threshold.subtract(BigInteger.ONE))))
    }

    @Test
    fun aNestedBatchIsListedOneLevelDownAndItsUnlimitedApprovalIsWarned() {
        val approveBytes = ContractCall.Approve(SPENDER, MAX_UINT256).encode()!!
        val approve = InnerCall(
            target = null,
            allowFailure = null,
            value = null,
            calldata = approveBytes,
            call = ContractCall.decode(approveBytes),
            nested = null,
        )
        val nestedBytes = encodeMulticall(MulticallShape.BYTES_ARRAY, deadline = null, calls = listOf(approve))!!
        // The outer call is a Multicall3 call to USDC whose calldata is a batch of its own, so the approval sits two levels down.
        val outer = InnerCall(
            target = USDC,
            allowFailure = false,
            value = null,
            calldata = nestedBytes,
            call = null,
            nested = Multicall(deadline = null, calls = listOf(approve)),
        )
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(outer))!!

        val review = reviewOf(txParsed(MULTICALL3, batch))

        assertEquals(SPENDER, review.sections.single { it.heading == "Calls" }.rows.single { it.label == "spender" }.value.text())
        assertEquals(UNLIMITED_WARNING, review.warnings.single { it.code == "evm.unlimited-approval" }.message)
    }

    @Test
    fun aMulticall3CallShowsItsAllowFailureFlag() {
        val approveBytes = ContractCall.Approve(SPENDER, BigInteger.TEN).encode()!!
        val call = InnerCall(
            target = USDC,
            allowFailure = true,
            value = null,
            calldata = approveBytes,
            call = ContractCall.decode(approveBytes),
            nested = null,
        )
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!

        val rows = reviewOf(txParsed(MULTICALL3, batch)).sections.single { it.heading == "Calls" }.rows

        assertEquals("true", rows.single { it.label == "allowFailure (bool)" }.value.text())
        assertEquals(SPENDER, rows.single { it.label == "spender" }.value.text())
    }

    @Test
    fun aWhitelistedProxyUnderWarnAndConfirmIsReviewedUnderARedWarningAndItsCheck() {
        val proxy = fooEntry().copy(upgradeable = true, proxy = ProxyBinding(implementationAddress = IMPLEMENTATION))
        val registry = REGISTRY.withWhitelist(listOf(proxy))

        val review = reviewOf(txParsed(NFT, fooCalldata()), builder(registry, proxyPolicy = ProxyPolicy.WARN_AND_CONFIRM))

        val warning = review.warnings.single { it.code == "evm.proxy" }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(ACK_UPGRADE, warning.acknowledge)
        assertEquals(IMPLEMENTATION, review.sections.single { it.heading == "Arguments" }.rows.single { it.label == "Implementation" }.value.text())
        // The default policy refuses the same proxy, so the second confirmation is the only way to sign it.
        refusalOf(txParsed(NFT, fooCalldata()), builder(registry))
    }

    @Test
    fun aWhitelistedProxyInsideAMulticallIsWarnedUnderWarnAndConfirmAndRefusedUnderTheDefault() {
        val proxy = fooEntry().copy(upgradeable = true, proxy = ProxyBinding(implementationAddress = IMPLEMENTATION))
        val registry = REGISTRY.withWhitelist(listOf(proxy))
        val call = InnerCall(target = NFT, allowFailure = false, value = null, calldata = fooCalldata(), call = null, nested = null)
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!

        val review = reviewOf(txParsed(MULTICALL3, batch), builder(registry, proxyPolicy = ProxyPolicy.WARN_AND_CONFIRM))

        val warning = review.warnings.single { it.code == "evm.proxy" }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(FieldPath("evm.data.call[0].function"), warning.field)
        assertEquals(ACK_UPGRADE, warning.acknowledge)
        assertEquals(IMPLEMENTATION, review.sections.single { it.heading == "Calls" }.rows.single { it.label == "Implementation" }.value.text())
        refusalOf(txParsed(MULTICALL3, batch), builder(registry))
    }

    @Test
    fun aPersonalMessageThatIsNotReadableTextShowsItsBytes() {
        val rows = reviewOf(personalParsed(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00))).sections.single { it.heading == "Message" }.rows

        assertEquals("0xfffe00", rows.single { it.label == "Message" }.value.text())
        assertEquals("3 bytes", rows.single { it.label == "Size" }.value.text())
    }

    @Test
    fun aPersonalMessageCountsCharactersAndBytesApart() {
        val rows = reviewOf(personalParsed("café".toByteArray(Charsets.UTF_8))).sections.single { it.heading == "Message" }.rows

        assertEquals("café", rows.single { it.label == "Message" }.value.text())
        assertEquals("4 characters, 5 bytes", rows.single { it.label == "Size" }.value.text())
    }

    @Test
    fun aLongPersonalMessageIsShownInFullWithItsLength() {
        val text = "x".repeat(300)

        val rows = reviewOf(personalParsed(text.toByteArray())).sections.single { it.heading == "Message" }.rows

        assertEquals(text, rows.single { it.label == "Message" }.value.text())
        assertEquals("300 characters, 300 bytes", rows.single { it.label == "Size" }.value.text())
    }

    /** U+E0041 is a tag character. It draws nothing, so the text reads "Sign in" while the signed bytes hold more: the bytes show. */
    @Test
    fun aPersonalMessageWithAnInvisibleTagCharacterShowsItsBytes() {
        val signed = "Sign in".toByteArray(Charsets.UTF_8) + String(Character.toChars(0xE0041)).toByteArray(Charsets.UTF_8)

        val rows = reviewOf(personalParsed(signed)).sections.single { it.heading == "Message" }.rows

        assertEquals("0x5369676e20696ef3a08181", rows.single { it.label == "Message" }.value.text())
        assertEquals("11 bytes", rows.single { it.label == "Size" }.value.text())
    }

    /** U+F0000 is private use. It draws nothing a reader can check, so a request whose origin holds it is refused. */
    @Test
    fun aRequestWhoseOriginHasAPrivateUseCharacterIsRefused() {
        val origin = "dapp" + String(Character.toChars(0xF0000))

        val refusal = refusalOf(personalParsed("hi".toByteArray(), origin = origin))

        assertEquals("The request names an origin with a character Lensly cannot show.", refusal.reason)
    }

    @Test
    fun aPersonalMessageThatNamesAChainSaysItIsValidOnEveryChain() {
        val review = reviewOf(personalParsed("hi".toByteArray(), chainId = 137L))

        assertEquals(
            listOf("This signature is valid on every EVM chain, not only the chain the request names."),
            review.warnings.filter { it.code == "evm.personal-message.chain" }.map { it.message },
        )
        assertEquals(Danger.CAUTION, review.warnings.single { it.code == "evm.personal-message.chain" }.level)
    }

    @Test
    fun aBip44AddressKeyIsACautionAndAnAccountKeyIsNot() {
        assertEquals(listOf(BIP44_CAUTION), bip44CautionsOf("m/44'/60'/0'/0/1"))
        assertEquals(listOf(BIP44_CAUTION), bip44CautionsOf("m/44'/60'/0'/0/12"))
        assertEquals(emptyList(), bip44CautionsOf("m/44'/60'/0'/0/0"))
        assertEquals(emptyList(), bip44CautionsOf("m/44'/60'/2'/0/0"))
        assertEquals(emptyList(), bip44CautionsOf(null))
    }

    @Test
    fun aRefusedProxyShowsItsApprovedCodeHashWithItsPrefix() {
        val codeHash = "0x" + "ab".repeat(32)
        val proxy = fooEntry().copy(upgradeable = true, proxy = ProxyBinding(implementationAddress = IMPLEMENTATION, implementationCodeHash = codeHash))

        val refused = refusalOf(txParsed(NFT, fooCalldata()), builder(REGISTRY.withWhitelist(listOf(proxy))))

        assertEquals("0x" + List(8) { "abababab" }.joinToString(" "), refused.rows.single { it.label == "Approved Code Hash" }.value.text())
        // The rows of a refused call, in the order its screen shows them: the call's own rows, then the proxy's binding, then the calldata.
        assertEquals(
            listOf("Contract", "Network", "Value", "Selector", "Approved Implementation", "Approved Code Hash", "Calldata"),
            refused.rows.take(7).map { it.label },
        )
    }

    /** The CAUTIONs a personal message of [path] gets, as their texts: none when [path] is null. */
    private fun bip44CautionsOf(path: String?): List<String> =
        assertIs<EvmReviewResult.Ok>(builder().review(personalParsed("hi".toByteArray()), SENDER, path)).review.warnings
            .filter { it.code == "evm.bip44-address" }.map { it.message }
}

private const val BIP44_CAUTION = "This key is a BIP44 address key, not one of this wallet's accounts. Check its address and path before you sign."

/** The parsed request of a personal message of [signData] on Monad testnet, as a wallet sends it, or on [chainId] when it names one. */
private fun personalParsed(signData: ByteArray, origin: String = "dapp.example", chainId: Long? = 10143L): ParsedRequest = EvmRequestParser.parsedOf(
    EthSignRequest(
        requestId = null,
        signData = signData,
        dataType = DataType.PERSONAL_MESSAGE,
        chainId = chainId,
        derivationPath = PATH,
        sourceFingerprint = null,
        address = null,
        origin = origin,
    ),
)

/** A whitelisted `foo(address,uint256)` on [NFT]: a recipient and an amount, which the signed list approves. */
private fun fooEntry(): WhitelistEntry {
    val signature = "foo(address,uint256)"
    return WhitelistEntry(
        chainId = 1,
        contract = NFT,
        selector = WhitelistEntry.selectorOf(signature),
        signature = signature,
        meaning = "Moves the amount to the recipient.",
        params = listOf(WhitelistParam("to", "address"), WhitelistParam("amount", "uint256")),
        upgradeable = false,
    )
}

/** The calldata of `foo(SPENDER, 42)`: the selector, then one word per argument. */
private fun fooCalldata(): ByteArray =
    Numeric.hexStringToByteArray(fooEntry().selector) + word(BigInteger(SPENDER.removePrefix("0x"), 16)) + word(BigInteger.valueOf(42))

/** A whitelisted `setData(bytes)` on [NFT]: one bytes argument, which the signed list approves. */
private fun bytesEntry(): WhitelistEntry {
    val signature = "setData(bytes)"
    return WhitelistEntry(
        chainId = 1,
        contract = NFT,
        selector = WhitelistEntry.selectorOf(signature),
        signature = signature,
        meaning = "Stores the data on the contract.",
        params = listOf(WhitelistParam("data", "bytes")),
        upgradeable = false,
    )
}

/** The calldata of a call with one bytes argument: the selector, the offset, the length, then the payload padded to whole words. */
private fun bytesCall(selector: String, payload: ByteArray): ByteArray {
    val padding = ByteArray((32 - payload.size % 32) % 32)
    return Numeric.hexStringToByteArray(selector) + word(BigInteger.valueOf(32)) + word(BigInteger.valueOf(payload.size.toLong())) +
        payload + padding
}

/** A 32-byte ABI word holding [value]. */
private fun word(value: BigInteger): ByteArray {
    val bytes = value.toByteArray().takeLast(32).toByteArray()
    return ByteArray(32 - bytes.size) + bytes
}
