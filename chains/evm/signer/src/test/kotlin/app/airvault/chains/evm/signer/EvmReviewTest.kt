package app.airvault.chains.evm.signer

import app.airvault.abi.AbiDecoder
import app.airvault.abi.AbiValue
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
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
import app.lensly.core.registry.NetworkKind
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
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

private const val USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val USDC_LOWER = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
private const val SPENDER = "0x1111111111111111111111111111111111111111"
private const val NFT = "0x2222222222222222222222222222222222222222"
private const val MULTICALL3 = "0xcA11bde05977b3631167028862bE2a173976CA11"
private const val FAKE_BATCH = "0x00000000000000000000000000000000DeaDBeef"
private const val SENDER = "0x3333333333333333333333333333333333333333"
private const val APPROVE_SELECTOR = "0x095ea7b3"
private const val UNLIMITED_WARNING = "Unlimited approval: this contract can spend all your USDC"
private const val SET_APPROVAL_WARNING = "Grants control of ALL your NFTs in this collection"
private const val UNKNOWN_WARNING =
    "Unknown contract call. Lensly does not read this call, so it cannot say what it does. Check the contract and the calldata before you sign."
private const val ACK_UNLIMITED = "I understand this approval is unlimited."
private const val ACK_ALL_NFTS = "I understand this grants control of all NFTs in this collection."
private const val ACK_FOREIGN_FROM = "I understand these tokens move out of another account."
private const val FOREIGN = "0x5555555555555555555555555555555555555555"
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

private const val HYPE_SYSTEM = "0x2222222222222222222222222222222222222222"
private const val TOKEN_SYSTEM = "0x20000000000000000000000000000000000000c8"
private const val HYPE_NEAR_MISS = "0x2222222222222222222222222222222222222223"
private const val TOKEN_NEAR_MISS = "0x21000000000000000000000000000000000000c8"
private const val HYPEREVM_TOKEN = "0x1111111111111111111111111111111111111111"
/** USDT0 on HyperEVM, linked to HyperCore token 268 (0x10c), so its own system address is 0x2000...010c. */
private const val HYPEREVM_USDT0 = "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb"
/** A transaction names its contract in checksum form. */
private val HYPEREVM_USDT0_TX = org.web3j.crypto.Keys.toChecksumAddress(HYPEREVM_USDT0)
private const val USDT0_SYSTEM = "0x200000000000000000000000000000000000010c"
private const val HYPEREVM_UNLINKED = "0x3333333333333333333333333333333333333333"

/** HyperEVM mainnet (999) and testnet (998), as the registry names them. HYPEREVM_TOKEN is the one registered token. */
private val HYPEREVM_REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(999, "HyperEVM", "HYPE", 18, FeeModel.L1, null, testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
        NetworkEntry(998, "HyperEVM Testnet", "HYPE", 18, FeeModel.L1, null, testnet = true, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
    ),
    tokens = listOf(
        TokenEntry(999, HYPEREVM_TOKEN, "HYT", "HyperEVM Test Token", 18),
        TokenEntry(999, HYPEREVM_USDT0, "USDT0", "USDT0", 6),
    ),
)

/** Monad testnet (10143), as the debug catalog names it, and Ethereum with USDC. */
private val REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(10143, "Monad Testnet", "MON", 18, FeeModel.L1, "MonadVision", testnet = true, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
        NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
    ),
    tokens = listOf(TokenEntry(1, USDC_LOWER, "USDC", "USD Coin", 6), TokenEntry(1, WETH_ADDRESS, "WETH", "Wrapped Ether", 18)),
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

    private val DRAINER = "0x6666666666666666666666666666666666666666"

    private fun drainerRegistry(chainId: Long? = null) = REGISTRY.withDenylist(
        listOf(DenylistEntry(chainId = chainId, party = DRAINER, severity = DenylistSeverity.BLOCK, reason = "Known drainer.")),
    )

    @Test
    fun aPartyEntryRefusesACallThatNamesTheDrainerAsSpenderOperatorOrRecipient() {
        val reviewer = builder(drainerRegistry())
        val calls = listOf(
            USDC to ContractCall.Approve(DRAINER, BigInteger.valueOf(1000)),
            NFT to ContractCall.SetApprovalForAll(DRAINER, true),
            USDC to ContractCall.Transfer(DRAINER, BigInteger.ONE),
            NFT to ContractCall.SafeTransferFrom(SENDER, DRAINER, BigInteger.ONE),
        )

        for ((target, call) in calls) {
            assertEquals("Known drainer.", refusalOf(txParsed(target, call.encode()!!), reviewer).reason, call.name)
        }
    }

    private fun word(address: String): ByteArray = ByteArray(12) + Numeric.hexStringToByteArray(address)

    private fun selector(hex: String): ByteArray = Numeric.hexStringToByteArray(hex)

    @Test
    fun aPartyEntryRefusesCalldataThatDoesNotDecodeExactlyButNamesTheDrainerInAWord() {
        // Under the default policy these calls are refused as unreadable anyway. Under WARN they reach the denylist, which must still refuse.
        val reviewer = builder(drainerRegistry(), unknownCalls = UnknownCallPolicy.WARN)
        val one = ByteArray(31) + byteArrayOf(1)
        val dirtyUpperBytes = ByteArray(32) { 0xff.toByte() }.also { Numeric.hexStringToByteArray(DRAINER).copyInto(it, 12) }
        val calls = mapOf(
            "approve with a trailing byte" to selector("0x095ea7b3") + word(DRAINER) + one + byteArrayOf(1),
            "approve with dirty upper bytes" to selector("0x095ea7b3") + dirtyUpperBytes + one,
            "four-argument safeTransferFrom" to
                selector("0xb88d4fde") + word(SENDER) + word(DRAINER) + one + word(SPENDER) + ByteArray(32) + ByteArray(32),
            "ERC-1155 safeTransferFrom" to
                selector("0xf242432a") + word(SENDER) + word(DRAINER) + one + one + word(SPENDER) + ByteArray(32),
            "ERC-2612 permit" to
                selector("0xd505accf") + word(SENDER) + word(DRAINER) + one + one + ByteArray(32) + ByteArray(32) + ByteArray(32),
        )

        for ((name, data) in calls) {
            assertEquals("Known drainer.", refusalOf(txParsed(USDC, data), reviewer).reason, name)
        }
    }

    @Test
    fun aPartyEntryMatchesWholeWordsOnly() {
        // The drainer's 20 bytes sit across two words, and its last bytes trail the calldata as a partial word.
        val straddling = selector("0x095ea7b3") + ByteArray(32) + Numeric.hexStringToByteArray(DRAINER).copyOfRange(0, 10) +
            ByteArray(32 - 10)
        val partialWord = selector("0x095ea7b3") + ByteArray(32) + Numeric.hexStringToByteArray(DRAINER)

        for (data in listOf(straddling, partialWord)) {
            val result = builder(drainerRegistry(), unknownCalls = UnknownCallPolicy.WARN).review(txParsed(USDC, data), SENDER)
            assertTrue(result !is EvmReviewResult.Refused || result.reason != "Known drainer.")
        }
    }

    @Test
    fun aPartyEntryLeavesACallToAnotherAddressAndAContractOnlyEntryAsTheyWere() {
        val approveOther = ContractCall.Approve(SPENDER, BigInteger.valueOf(1000)).encode()!!

        assertIs<EvmReviewResult.Ok>(builder(drainerRegistry()).review(txParsed(USDC, approveOther), SENDER))
        // A contract entry still matches only the contract the call goes to, never a counterparty.
        val contractOnly = REGISTRY.withDenylist(
            listOf(DenylistEntry(chainId = 1, contract = DRAINER, severity = DenylistSeverity.BLOCK, reason = "Known drainer.")),
        )
        val approveDrainer = ContractCall.Approve(DRAINER, BigInteger.valueOf(1000)).encode()!!
        assertIs<EvmReviewResult.Ok>(builder(contractOnly).review(txParsed(USDC, approveDrainer), SENDER))
    }

    @Test
    fun aPartyEntryOfAnotherChainDoesNotMatch() {
        val approveDrainer = ContractCall.Approve(DRAINER, BigInteger.valueOf(1000)).encode()!!

        assertIs<EvmReviewResult.Ok>(builder(drainerRegistry(chainId = 10)).review(txParsed(USDC, approveDrainer), SENDER))
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
    fun anAddressArgumentOfAnApprovedCallIsAnAddressRow() {
        val review = reviewOf(txParsed(NFT, fooCalldata()), builder(REGISTRY.withWhitelist(listOf(fooEntry()))))

        val rows = review.sections.single { it.heading == "Arguments" }.rows
        assertEquals(DisplayValue.Address(review.chain, SPENDER), rows.single { it.label == "to (address)" }.value)
        assertIs<DisplayValue.Text>(rows.single { it.label == "amount (uint256)" }.value)
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
            txParsed(MULTICALL3, batch, value = BigInteger("1000000000000000")),
            builder(REGISTRY.withWhitelist(listOf(fooEntry()))),
        )

        // The call's value is what the batch sends, so the header amount equals it (the contract rejects a batch whose call values differ from the amount). The call's value shows once, in the batch's flags, and no row repeats it.
        assertEquals("0.001 ETH", review.sections.first().rows.single { it.label == "Amount" }.value.text())
        val rows = review.sections.single { it.heading == "Calls" }.rows
        assertEquals("0.001 ETH", rows.single { it.label == "value (uint256)" }.value.text())
        assertTrue(rows.none { it.label == "Value" })
    }

    @Test
    fun anAggregate3ValueBatchShowsEachStepWithItsOwnValue() {
        val batch = lidoBatch(MulticallShape.AGGREGATE3_VALUE, ONE_ETH, TWO_ETH)

        val review = reviewOf(txParsed(MULTICALL3, batch, value = THREE_ETH), builder(REGISTRY.withWhitelist(listOf(lidoEntry()))))

        assertEquals("3 ETH", review.sections.first().rows.single { it.label == "Amount" }.value.text())
        val lines = review.sections.single { it.heading == "Calls" }.rows.filter { it.label.startsWith("Stake ") }.map { it.label }
        assertEquals(listOf("Stake 1 ETH with Lido for stETH", "Stake 2 ETH with Lido for stETH"), lines)
    }

    @Test
    fun anAggregate3ValueBatchWhoseValuesDoNotAddUpToTheAmountIsRefused() {
        val batch = lidoBatch(MulticallShape.AGGREGATE3_VALUE, ONE_ETH, TWO_ETH)

        refusalOf(txParsed(MULTICALL3, batch, value = ONE_ETH), builder(REGISTRY.withWhitelist(listOf(lidoEntry()))))
    }

    @Test
    fun anAggregate3BatchThatSendsAmountsShowsNoValueAndStrandsIt() {
        val batch = lidoBatch(MulticallShape.AGGREGATE3, null, null)

        val review = reviewOf(txParsed(MULTICALL3, batch, value = ONE_ETH), builder(REGISTRY.withWhitelist(listOf(lidoEntry()))))

        // Multicall3 calls each target with zero value, so no step stakes 1 ETH, and the review says the amount is lost.
        val lines = review.sections.single { it.heading == "Calls" }.rows.filter { it.label.startsWith("Stake ") }.map { it.label }
        assertEquals(listOf("Stake 0 ETH with Lido for stETH", "Stake 0 ETH with Lido for stETH"), lines)
        assertEquals(Danger.DANGER, review.warnings.single { it.code == "evm.stranded-value" }.level)
    }

    @Test
    fun anAggregate3ValueCallThatMayFailAndCarriesValueHasACautionThatItsValueStaysInMulticall3() {
        val review = reviewOf(txParsed(MULTICALL3, aggregate3ValueTransfer(allowFailure = true, value = BigInteger.ONE), value = BigInteger.ONE))

        val caution = review.warnings.single { it.code == "evm.failing-call-value" }
        assertEquals(Danger.CAUTION, caution.level)
        assertEquals("If this step fails, its value stays in the Multicall3 contract and can be taken by anyone.", caution.message)
    }

    /** The approved batch contract [FAKE_BATCH], which is not Multicall3, running [shape] (the entry's selector is the shape's). */
    private fun fakeBatchEntry(shape: MulticallShape) = WhitelistEntry(
        chainId = 1,
        contract = FAKE_BATCH.lowercase(),
        selector = "0x${shape.selector}",
        signature = shape.signature,
        meaning = "Runs the calls in order.",
        params = listOf(WhitelistParam("calls", shape.signature.substringAfter("(").removeSuffix(")"))),
        upgradeable = false,
    )

    @Test
    fun aBatchContractThatIsNotMulticall3IsNamedByItsAddressInTheStrandedAndFailingValueWarnings() {
        val short = "0x0000 0000 … DeaD Beef"
        val stranded = reviewOf(
            txParsed(FAKE_BATCH, lidoBatch(MulticallShape.AGGREGATE3, null, null), value = ONE_ETH),
            builder(REGISTRY.withWhitelist(listOf(lidoEntry(), fakeBatchEntry(MulticallShape.AGGREGATE3)))),
        )
        assertEquals(
            "This batch sends native currency, but its calls receive none of it. The amount stays in the contract at $short and you cannot get it back.",
            stranded.warnings.single { it.code == "evm.stranded-value" }.message,
        )

        val failing = reviewOf(
            txParsed(FAKE_BATCH, aggregate3ValueTransfer(allowFailure = true, value = BigInteger.ONE), value = BigInteger.ONE),
            builder(REGISTRY.withWhitelist(listOf(fakeBatchEntry(MulticallShape.AGGREGATE3_VALUE)))),
        )
        assertEquals(
            "If this step fails, its value stays in the contract at $short and can be taken by anyone.",
            failing.warnings.single { it.code == "evm.failing-call-value" }.message,
        )
        (stranded.warnings + failing.warnings).forEach { assertTrue("Multicall3" !in it.message, it.message) }
    }

    @Test
    fun anAggregate3ValueCallThatCannotFailOrCarriesNoValueHasNoFailingValueCaution() {
        val cannotFail = reviewOf(txParsed(MULTICALL3, aggregate3ValueTransfer(allowFailure = false, value = BigInteger.ONE), value = BigInteger.ONE))
        val noValue = reviewOf(txParsed(MULTICALL3, aggregate3ValueTransfer(allowFailure = true, value = BigInteger.ZERO)))

        assertTrue(cannotFail.warnings.none { it.code == "evm.failing-call-value" })
        assertTrue(noValue.warnings.none { it.code == "evm.failing-call-value" })
    }

    @Test
    fun anAggregate3BatchThatSendsNothingHasNoStrandedValueWarning() {
        val batch = lidoBatch(MulticallShape.AGGREGATE3, null, null)

        val review = reviewOf(txParsed(MULTICALL3, batch), builder(REGISTRY.withWhitelist(listOf(lidoEntry()))))

        assertTrue(review.warnings.none { it.code == "evm.stranded-value" })
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
    fun aNativeTransferOfHypeToTheHypeSystemAddressOnHyperEvmIsLabelledMoveToHyperCore() {
        for (chainId in listOf(999L, 998L)) {
            val review = reviewOf(txParsed(HYPE_SYSTEM, ByteArray(0), BigInteger.TEN.pow(18), chainId), builder(HYPEREVM_REGISTRY))

            assertEquals("Move to HyperCore", review.title)
        }
    }

    @Test
    fun anErc20TransferToItsOwnTokenSystemAddressOnHyperEvmIsLabelledMoveToHyperCore() {
        val call = ContractCall.Transfer(USDT0_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val review = reviewOf(txParsed(HYPEREVM_USDT0_TX, call, chainId = 999), builder(HYPEREVM_REGISTRY))

        assertEquals("Move to HyperCore", review.title)
        assertTrue(review.warnings.none { it.code == "evm.hypercore-system-destination" })
        // The label adds to the review: the call's function and its recipient are still shown.
        assertTrue(review.sections.flatMap { it.rows }.any { it.label == "to" }, "the recipient row is still shown")
    }

    @Test
    fun anErc20TransferToAnotherTokensSystemAddressKeepsItsFunctionTitleAndIsADanger() {
        // USDT0 (index 268) sent to the system address of token 200 (0xc8): HyperCore may not credit it.
        val call = ContractCall.Transfer(TOKEN_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val review = reviewOf(txParsed(HYPEREVM_USDT0_TX, call, chainId = 999), builder(HYPEREVM_REGISTRY))

        assertEquals("transfer", review.title)
        assertEquals(listOf(Danger.DANGER), review.warnings.filter { it.code == "evm.hypercore-system-destination" }.map { it.level })
        // The HYPE system address belongs to no ERC20 token either.
        val toHype = ContractCall.Transfer(HYPE_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val hypeReview = reviewOf(txParsed(HYPEREVM_USDT0_TX, toHype, chainId = 999), builder(HYPEREVM_REGISTRY))
        assertEquals("transfer", hypeReview.title)
        assertTrue(hypeReview.warnings.any { it.code == "evm.hypercore-system-destination" })
    }

    @Test
    fun anErc20TransferOfATokenWithNoKnownHyperCoreIndexToASystemAddressKeepsItsFunctionTitleAndIsADanger() {
        val call = ContractCall.Transfer(TOKEN_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val review = reviewOf(txParsed(HYPEREVM_TOKEN, call, chainId = 999), builder(HYPEREVM_REGISTRY))

        assertEquals("transfer", review.title)
        assertTrue(review.warnings.any { it.code == "evm.hypercore-system-destination" && it.level == Danger.DANGER })
    }

    @Test
    fun anErc20TransferOfAnUnlistedTokenToASystemAddressSaysLenslyCannotConfirmItBelongsToTheToken() {
        val call = ContractCall.Transfer(TOKEN_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val review = reviewOf(txParsed(HYPEREVM_TOKEN, call, chainId = 999), builder(HYPEREVM_REGISTRY))

        val warning = review.warnings.single { it.code == "evm.hypercore-system-destination" }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(
            "This sends tokens to a HyperCore system address. Lensly cannot confirm this system address belongs to this token. " +
                "Funds sent to the wrong system address may be lost. Check the address before you sign.",
            warning.message,
        )
    }

    @Test
    fun anErc20TransferOfAListedTokenToAnotherTokensSystemAddressStillSaysItDoesNotBelongToIt() {
        val foreign = ContractCall.Transfer(TOKEN_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val review = reviewOf(txParsed(HYPEREVM_USDT0_TX, foreign, chainId = 999), builder(HYPEREVM_REGISTRY))

        val warning = review.warnings.single { it.code == "evm.hypercore-system-destination" }
        assertTrue(warning.message.contains("does not belong to this token"))
    }

    @Test
    fun anErc20TransferByAnUnlinkedContractToATokenSystemAddressKeepsItsFunctionTitle() {
        val call = ContractCall.Transfer(TOKEN_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val review = reviewOf(txParsed(HYPEREVM_UNLINKED, call, chainId = 999), builder(HYPEREVM_REGISTRY))

        assertEquals("transfer", review.title)
    }

    @Test
    fun anErc20TransferFromToAnotherTokensSystemAddressIsADangerAndToItsOwnIsAMove() {
        val foreign = ContractCall.TransferFrom(SENDER, TOKEN_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val review = reviewOf(txParsed(HYPEREVM_USDT0_TX, foreign, chainId = 999), builder(HYPEREVM_REGISTRY))
        assertEquals("transferFrom", review.title)
        assertEquals(listOf(Danger.DANGER), review.warnings.filter { it.code == "evm.hypercore-system-destination" }.map { it.level })

        val own = ContractCall.TransferFrom(SENDER, USDT0_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        val move = reviewOf(txParsed(HYPEREVM_USDT0_TX, own, chainId = 999), builder(HYPEREVM_REGISTRY))
        assertEquals("Move to HyperCore", move.title)
        assertTrue(move.warnings.none { it.code == "evm.hypercore-system-destination" })
    }

    @Test
    fun aTransferToAnotherTokensSystemAddressInsideAMulticallIsADangerAndToItsOwnIsNot() {
        fun batchOf(recipient: String): ParsedRequest {
            val transfer = ContractCall.Transfer(recipient, BigInteger.valueOf(1_000_000)).encode()!!
            val call = InnerCall(
                target = HYPEREVM_USDT0_TX,
                allowFailure = false,
                value = null,
                calldata = transfer,
                call = ContractCall.decode(transfer),
                nested = null,
            )
            return txParsed(MULTICALL3, encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!, chainId = 999)
        }

        val foreign = reviewOf(batchOf(TOKEN_SYSTEM), builder(HYPEREVM_REGISTRY))
        val danger = foreign.warnings.single { it.code == "evm.hypercore-system-destination" }
        assertEquals(Danger.DANGER, danger.level)
        assertEquals(FieldPath("evm.data.call[0].function"), danger.field)

        val own = reviewOf(batchOf(USDT0_SYSTEM), builder(HYPEREVM_REGISTRY))
        assertTrue(own.warnings.none { it.code == "evm.hypercore-system-destination" })
    }

    @Test
    fun aNativeSendToATokenSystemAddressOnHyperEvmIsADangerButToTheHypeOneIsNot() {
        val review = reviewOf(txParsed(org.web3j.crypto.Keys.toChecksumAddress(TOKEN_SYSTEM), ByteArray(0), BigInteger.TEN.pow(18), chainId = 999), builder(HYPEREVM_REGISTRY))
        assertEquals("Native Transfer", review.title)
        assertEquals(listOf(Danger.DANGER), review.warnings.filter { it.code == "evm.hypercore-system-destination" }.map { it.level })

        val toHype = reviewOf(txParsed(HYPE_SYSTEM, ByteArray(0), BigInteger.TEN.pow(18), chainId = 999), builder(HYPEREVM_REGISTRY))
        assertTrue(toHype.warnings.none { it.code == "evm.hypercore-system-destination" })
        val elsewhere = reviewOf(txParsed(org.web3j.crypto.Keys.toChecksumAddress(TOKEN_SYSTEM), ByteArray(0), BigInteger.TEN.pow(18), chainId = 1))
        assertTrue(elsewhere.warnings.none { it.code == "evm.hypercore-system-destination" })
    }

    @Test
    fun aNativeSendWithCallDataToATokenSystemAddressOnHyperEvmGetsTheSystemAddressDanger() {
        // An unknown call is refused under the Block policy, so the warning is read under the Warn policy.
        val warned = builder(HYPEREVM_REGISTRY, unknownCalls = UnknownCallPolicy.WARN)
        val data = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val to = org.web3j.crypto.Keys.toChecksumAddress(TOKEN_SYSTEM)
        val review = reviewOf(txParsed(to, data, BigInteger.TEN.pow(18), chainId = 999), warned)
        assertEquals(listOf(Danger.DANGER), review.warnings.filter { it.code == "evm.hypercore-system-destination" }.map { it.level })

        // HYPE's own address, a zero value and another chain are not warned.
        val toHype = reviewOf(txParsed(HYPE_SYSTEM, data, BigInteger.TEN.pow(18), chainId = 999), warned)
        assertTrue(toHype.warnings.none { it.code == "evm.hypercore-system-destination" })
        val noValue = reviewOf(txParsed(to, data, chainId = 999), warned)
        assertTrue(noValue.warnings.none { it.code == "evm.hypercore-system-destination" })
        val elsewhere = reviewOf(txParsed(to, data, BigInteger.TEN.pow(18), chainId = 1), builder(unknownCalls = UnknownCallPolicy.WARN))
        assertTrue(elsewhere.warnings.none { it.code == "evm.hypercore-system-destination" })
    }

    @Test
    fun aSystemAddressTransferOutsideHyperEvmOrAtANearMissAddressIsNotLabelled() {
        assertEquals("Native Transfer", reviewOf(txParsed(HYPE_SYSTEM, ByteArray(0), BigInteger.TEN.pow(18), chainId = 1)).title)
        val tokenCall = ContractCall.Transfer(TOKEN_SYSTEM, BigInteger.valueOf(1_000_000)).encode()!!
        assertEquals("transfer", reviewOf(txParsed(USDC, tokenCall, chainId = 1)).title)
        assertEquals(
            "Native Transfer",
            reviewOf(txParsed(HYPE_NEAR_MISS, ByteArray(0), BigInteger.TEN.pow(18), chainId = 999), builder(HYPEREVM_REGISTRY)).title,
        )
        val nearMissCall = ContractCall.Transfer(TOKEN_NEAR_MISS, BigInteger.valueOf(1_000_000)).encode()!!
        assertEquals("transfer", reviewOf(txParsed(HYPEREVM_TOKEN, nearMissCall, chainId = 999), builder(HYPEREVM_REGISTRY)).title)
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
    fun aTransferFromOfTheSignersOwnAccountHasNoForeignFromWarning() {
        val review =
            reviewOf(txParsed(USDC, ContractCall.TransferFrom(SENDER.lowercase(), SPENDER, BigInteger.valueOf(1_500_000)).encode()!!))

        assertTrue(review.warnings.none { it.code == "evm.foreign-from" })
    }

    @Test
    fun aTransferFromOfAnotherAccountIsDangerAndShowsThatAddress() {
        val review = reviewOf(txParsed(USDC, ContractCall.TransferFrom(FOREIGN, SPENDER, BigInteger.valueOf(1_500_000)).encode()!!))

        val warning = review.warnings.single { it.code == "evm.foreign-from" }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(
            "Moves tokens out of $FOREIGN, which is not your account. Sign only if that account approved this transfer.",
            warning.message,
        )
        assertEquals(FieldPath("evm.data.from"), warning.field)
        assertEquals(ACK_FOREIGN_FROM, warning.acknowledge)
    }

    @Test
    fun aTransferFromOfAnotherAccountOnAnUnregisteredContractIsStillDanger() {
        val review = reviewOf(txParsed(NFT, ContractCall.TransferFrom(FOREIGN, SPENDER, BigInteger.valueOf(7)).encode()!!))

        assertEquals(Danger.DANGER, review.warnings.single { it.code == "evm.foreign-from" }.level)
        assertNotNull(review.warnings.single { it.code == "evm.registry" }.acknowledge)
    }

    @Test
    fun aSafeTransferFromOfAnotherAccountIsDangerAndNamesItsCheck() {
        val review = reviewOf(txParsed(NFT, ContractCall.SafeTransferFrom(FOREIGN, SPENDER, BigInteger.valueOf(7)).encode()!!))

        val warning = review.warnings.single { it.code == "evm.foreign-from" }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(FieldPath("evm.data.from"), warning.field)
        assertEquals(ACK_FOREIGN_FROM, warning.acknowledge)
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
        val outer = nestedMulticall3Batch(MULTICALL3)

        val review = reviewOf(txParsed(MULTICALL3, outer))

        assertEquals(SPENDER, review.sections.single { it.heading == "Calls" }.rows.single { it.label == "spender" }.value.text())
        assertEquals(UNLIMITED_WARNING, review.warnings.single { it.code == "evm.unlimited-approval" }.message)
    }

    @Test
    fun aNestedBatchOnAContractNotKnownToRunItIsRefusedAsAnUnknownCall() {
        val refused = refusalOf(txParsed(MULTICALL3, nestedMulticall3Batch(FAKE_BATCH)))

        assertEquals("Unknown Contract Call", refused.reason)
    }

    @Test
    fun aBatchOnAContractNotKnownToRunItIsRefusedAsAnUnknownCall() {
        val refused = refusalOf(txParsed(FAKE_BATCH, aggregate3ValueTransfer(), value = ONE_ETH))

        assertEquals("Unknown Contract Call", refused.reason)
    }

    @Test
    fun aBatchOnAContractNotKnownToRunItIsDangerUnderTheWarnPolicy() {
        val review = reviewOf(txParsed(FAKE_BATCH, aggregate3ValueTransfer(), value = ONE_ETH), builder(unknownCalls = UnknownCallPolicy.WARN))

        val unknown = review.warnings.single { it.code == "evm.unknown-call" }
        assertEquals(Danger.DANGER, unknown.level)
        assertEquals(ACK_UNKNOWN, unknown.acknowledge)
        assertTrue(review.sections.none { it.heading == "Calls" })
    }

    @Test
    fun aMulticall3BatchOnTheRealMulticall3ContractStillReviewsWithoutWarnings() {
        val review = reviewOf(txParsed(MULTICALL3, aggregate3ValueTransfer(), value = ONE_ETH))

        assertEquals("aggregate3Value", review.title)
        assertNotNull(review.sections.singleOrNull { it.heading == "Calls" })
        assertTrue(review.warnings.none { it.level == Danger.DANGER })
    }

    @Test
    fun aUniswapMulticallReadsOnlyOnAContractTheWhitelistApprovesItOn() {
        val approve = ContractCall.Approve(SPENDER, BigInteger.TEN).encode()!!
        val call = InnerCall(target = null, allowFailure = null, value = null, calldata = approve, call = ContractCall.decode(approve), nested = null)
        val batch = encodeMulticall(MulticallShape.BYTES_ARRAY, deadline = null, calls = listOf(call))!!
        val entry = WhitelistEntry(
            chainId = 1,
            contract = FAKE_BATCH.lowercase(),
            selector = "0x${MulticallShape.BYTES_ARRAY.selector}",
            signature = MulticallShape.BYTES_ARRAY.signature,
            meaning = "Runs the calls in order.",
            params = listOf(WhitelistParam("data", "bytes[]")),
            upgradeable = false,
        )

        assertEquals("Unknown Contract Call", refusalOf(txParsed(FAKE_BATCH, batch)).reason)
        assertEquals("multicall", reviewOf(txParsed(FAKE_BATCH, batch), builder(REGISTRY.withWhitelist(listOf(entry)))).title)
    }

    @Test
    fun anUpgradeableWhitelistedBatchContractIsRefusedOrWarnedLikeAnyWhitelistedProxy() {
        val approve = ContractCall.Approve(SPENDER, BigInteger.TEN).encode()!!
        val call = InnerCall(target = null, allowFailure = null, value = null, calldata = approve, call = ContractCall.decode(approve), nested = null)
        val batch = encodeMulticall(MulticallShape.BYTES_ARRAY, deadline = null, calls = listOf(call))!!
        val entry = WhitelistEntry(
            chainId = 1,
            contract = FAKE_BATCH.lowercase(),
            selector = "0x${MulticallShape.BYTES_ARRAY.selector}",
            signature = MulticallShape.BYTES_ARRAY.signature,
            meaning = "Runs the calls in order.",
            params = listOf(WhitelistParam("data", "bytes[]")),
            upgradeable = true,
            proxy = ProxyBinding(implementationAddress = IMPLEMENTATION),
        )
        val registry = REGISTRY.withWhitelist(listOf(entry))

        refusalOf(txParsed(FAKE_BATCH, batch), builder(registry))
        refusalOf(txParsed(FAKE_BATCH, batch), builder(registry, proxyPolicy = ProxyPolicy.REFUSE_ALL_PROXIES))
        val review = reviewOf(txParsed(FAKE_BATCH, batch), builder(registry, proxyPolicy = ProxyPolicy.WARN_AND_CONFIRM))

        assertEquals("multicall", review.title)
        val warning = review.warnings.single { it.code == "evm.proxy" }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(ACK_UPGRADE, warning.acknowledge)
        assertEquals(IMPLEMENTATION, review.sections.first().rows.single { it.label == "Implementation" }.value.text())
        assertNotNull(review.sections.singleOrNull { it.heading == "Calls" })
    }

    @Test
    fun aRouterSwapInsideAMulticall3BatchNamesMulticall3AsTheRecipientAndIsDanger() {
        // Multicall3 calls the router, so the router's message sender is Multicall3: the flag 0x...01 pays the swap output to Multicall3.
        // The same call straight to the router pays the signer.
        val commands = byteArrayOf(0x0b, 0x00)
        val inputs = listOf(urWrapInput(UR_ADDRESS_THIS), urSwapInput(UR_MSG_SENDER))
        val router = urEntry()
        val registry = REGISTRY.withWhitelist(listOf(router))
        val call = InnerCall(UR_V2_TX, allowFailure = false, value = ONE_ETH, calldata = urExecute(commands, inputs), call = null, nested = null)
        val batch = encodeMulticall(MulticallShape.AGGREGATE3_VALUE, deadline = null, calls = listOf(call))!!

        val viaBatch = reviewOf(txParsed(MULTICALL3, batch, value = ONE_ETH), builder(registry))
        val direct = reviewOf(txParsed(UR_V2_TX, urExecute(commands, inputs), value = ONE_ETH), builder(registry))

        val danger = viaBatch.warnings.single { it.code == "evm.steps" }
        assertEquals(Danger.DANGER, danger.level)
        assertEquals("This pays the swap output to 0xcA11 bde0 … 3976 CA11, not you", danger.message)
        assertEquals(ACK_STEP_PAYEE, danger.acknowledge)
        assertEquals(MULTICALL3, viaBatch.sections.flatMap { it.rows }.single { it.label == "Recipient" }.value.text())
        val steps = viaBatch.sections.flatMap { it.rows }.map { it.label }.filter { it.startsWith("Swap exactly") }
        assertEquals(listOf("Swap exactly 1 WETH for ≥ 3,000 USDC via Uniswap v3 (0.05%), sent to Multicall3"), steps)
        // Straight to the router the same call pays the signer: only the whitelist caution, no recipient row.
        assertEquals(listOf("evm.whitelisted-call"), direct.warnings.map { it.code })
        assertTrue(direct.sections.flatMap { it.rows }.none { it.label == "Recipient" })
    }

    @Test
    fun anAaveRecipientOtherThanTheSignerInAMulticall3BatchIsDangerWithTheRecipientRow() {
        val signature = "withdraw(address,uint256,address)"
        val entry = WhitelistEntry(
            chainId = 1,
            contract = AAVE_POOL,
            selector = WhitelistEntry.selectorOf(signature),
            signature = signature,
            meaning = "Withdraws a supplied asset.",
            params = listOf(WhitelistParam("asset", "address"), WhitelistParam("amount", "uint256"), WhitelistParam("to", "address")),
            upgradeable = false,
        )
        val registry = REGISTRY.withWhitelist(listOf(entry))
        val calldata = Numeric.hexStringToByteArray(entry.selector) + word(BigInteger(USDC.removePrefix("0x"), 16)) +
            word(BigInteger.valueOf(1_000_000)) + word(BigInteger(FOREIGN.removePrefix("0x"), 16))
        val call = InnerCall(AAVE_POOL, allowFailure = false, value = null, calldata = calldata, call = null, nested = null)
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!

        val review = reviewOf(txParsed(MULTICALL3, batch), builder(registry))

        val danger = review.warnings.single { it.code == "evm.steps" }
        assertEquals(Danger.DANGER, danger.level)
        assertEquals(ACK_STEP_PAYEE, danger.acknowledge)
        assertEquals(FOREIGN, review.sections.flatMap { it.rows }.single { it.label == "Recipient" }.value.text())
    }

    @Test
    fun aRouterSwapThatLeavesItsOutputInTheRouterIsADangerTheOwnerTicksDirectAndInABatch() {
        val registry = REGISTRY.withWhitelist(listOf(urEntry()))
        val swap = urSwapInput(UR_ADDRESS_THIS)
        val sweep = word(BigInteger(USDC_LOWER.removePrefix("0x"), 16)) + word(BigInteger(SENDER.removePrefix("0x"), 16)) + word(BigInteger.ZERO)
        fun direct(commands: ByteArray, inputs: List<ByteArray>) = reviewOf(txParsed(UR_V2_TX, urExecute(commands, inputs)), builder(registry))
        fun batched(commands: ByteArray, inputs: List<ByteArray>): Review {
            val call = InnerCall(UR_V2_TX, allowFailure = false, value = BigInteger.ZERO, calldata = urExecute(commands, inputs), call = null, nested = null)
            val batch = encodeMulticall(MulticallShape.AGGREGATE3_VALUE, deadline = null, calls = listOf(call))!!
            return reviewOf(txParsed(MULTICALL3, batch), builder(registry))
        }

        listOf(direct(byteArrayOf(0x00), listOf(swap)), batched(byteArrayOf(0x00), listOf(swap))).forEach { review ->
            val danger = review.warnings.single { it.code == "evm.router-leftover" }
            assertEquals(Danger.DANGER, danger.level)
            assertEquals("This leaves ETH or tokens in the router, where anyone can take them", danger.message)
            assertEquals(ACK_ROUTER_LEFTOVER, danger.acknowledge)
        }
        // A sweep to the signer after the swap takes the output out, so the review has no such warning.
        listOf(direct(byteArrayOf(0x00, 0x04), listOf(swap, sweep)), batched(byteArrayOf(0x00, 0x04), listOf(swap, sweep))).forEach { review ->
            assertTrue(review.warnings.none { it.code == "evm.router-leftover" }, "${review.warnings}")
        }
    }

    @Test
    fun aRouterStepThatPaysTheRoutersOwnAddressOrWrapsIntoTheRouterIsAlsoALeftoverDanger() {
        val registry = REGISTRY.withWhitelist(listOf(urEntry()))
        fun review(commands: ByteArray, inputs: List<ByteArray>) = reviewOf(txParsed(UR_V2_TX, urExecute(commands, inputs)), builder(registry))
        fun leftover(review: Review) = review.warnings.filter { it.code == "evm.router-leftover" }
        val wrap = byteArrayOf(0x0b)
        val swap = byteArrayOf(0x00)

        // The router's own address as the recipient does what the flag does, so it earns the same DANGER.
        leftover(review(swap, listOf(urSwapInput(UR_V2)))).single().let {
            assertEquals(Danger.DANGER, it.level)
            assertEquals("This leaves ETH or tokens in the router, where anyone can take them", it.message)
            assertEquals(ACK_ROUTER_LEFTOVER, it.acknowledge)
        }
        // A wrap into the router that nothing spends is a DANGER too, and the usual swap of the wrapped ETH is not.
        assertEquals(1, leftover(review(wrap, listOf(urWrapInput(UR_ADDRESS_THIS)))).size)
        assertEquals(emptyList(), leftover(review(byteArrayOf(0x0b, 0x00), listOf(urWrapInput(UR_ADDRESS_THIS), urSwapInput(SENDER)))))
        assertEquals(emptyList(), leftover(review(wrap, listOf(urWrapInput(SENDER)))))
    }

    @Test
    fun aRouterSwapToALookalikeOfTheSignerWarnsAndShowsTheRecipientAsAnAddressRow() {
        // SENDER is 0x3333...3333. The lookalike has the same first and last four hex characters, so its short form reads as the signer.
        val lookalike = "0x3333" + "0".repeat(32) + "3333"
        val registry = REGISTRY.withWhitelist(listOf(urEntry()))
        val commands = byteArrayOf(0x02, 0x00)
        val permit = word(BigInteger(USDC_LOWER.removePrefix("0x"), 16)) + word(BigInteger(lookalike.removePrefix("0x"), 16)) + word(BigInteger.valueOf(10_000_000_000))
        val inputs = listOf(permit, urSwapInput(lookalike, payerIsUser = true))

        val review = reviewOf(txParsed(UR_V2_TX, urExecute(commands, inputs)), builder(registry))

        val warnings = review.warnings.filter { it.code == "evm.steps" }
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.level == Danger.CAUTION && it.message.endsWith("0x3333 0000 … 0000 3333, not you") }, "$warnings")
        val recipients = review.sections.flatMap { it.rows }.filter { it.label == "Recipient" }
        assertEquals(listOf(Keys.toChecksumAddress(lookalike)), recipients.map { it.value.text() })
        assertIs<DisplayValue.Address>(recipients.single().value)
    }

    @Test
    fun aRouterSwapToTheSignerAddsNoWarningAndNoRecipientRow() {
        val registry = REGISTRY.withWhitelist(listOf(urEntry()))
        listOf(UR_MSG_SENDER, SENDER).forEach { recipient ->
            val review = reviewOf(txParsed(UR_V2_TX, urExecute(byteArrayOf(0x00), listOf(urSwapInput(recipient)))), builder(registry))

            assertEquals(listOf("evm.whitelisted-call"), review.warnings.map { it.code }, recipient)
            assertTrue(review.sections.flatMap { it.rows }.none { it.label == "Recipient" }, recipient)
        }
    }

    @Test
    fun aTransferFromOfTheSignerInsideAMulticall3BatchWarnsBecauseMulticall3SpendsTheApproval() {
        val transfer = ContractCall.TransferFrom(SENDER, FOREIGN, BigInteger.ONE).encode()!!
        val call = InnerCall(USDC, allowFailure = false, value = null, calldata = transfer, call = ContractCall.decode(transfer), nested = null)
        val batch = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(call))!!
        val direct = reviewOf(txParsed(USDC, transfer))

        val review = reviewOf(txParsed(MULTICALL3, batch))

        assertEquals(Danger.DANGER, review.warnings.single { it.code == "evm.foreign-from" }.level)
        assertTrue(direct.warnings.none { it.code == "evm.foreign-from" })
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
    fun aPersonalMessageThatIsA32ByteHashIsDangerAndNamesItsCheck() {
        val hash = Hash.sha3("any safe transaction".toByteArray())

        val review = reviewOf(personalParsed(hash))

        val danger = review.warnings.single { it.code == "evm.personal-message.hash" }
        assertEquals(Danger.DANGER, danger.level)
        assertEquals("I understand this signature may approve a transaction Lensly cannot show.", danger.acknowledge)
    }

    @Test
    fun aPersonalMessageOf32PrintableBytesIsDangerAndShowsItsBytesNextToTheText() {
        // A hash ground until every byte prints as a character reads as text, so the size alone decides.
        val text = "a".repeat(32)

        val review = reviewOf(personalParsed(text.toByteArray()))

        assertEquals(Danger.DANGER, review.warnings.single { it.code == "evm.personal-message.hash" }.level)
        val rows = review.sections.single { it.heading == "Message" }.rows
        assertEquals(text, rows.single { it.label == "Message" }.value.text())
        assertEquals("0x" + "61".repeat(32), rows.single { it.label == "Message Bytes" }.value.text())
    }

    @Test
    fun aPersonalMessageOfAnotherLengthShowsNoBytesRowWhenItReadsAsText() {
        val rows = reviewOf(personalParsed("a".repeat(31).toByteArray())).sections.single { it.heading == "Message" }.rows

        assertTrue(rows.none { it.label == "Message Bytes" })
    }

    @Test
    fun aReadablePersonalMessageOfAnotherLengthIsNotADanger() {
        val text31 = "a".repeat(31)

        for (text in listOf(text31, "hi", "a".repeat(100))) {
            val review = reviewOf(personalParsed(text.toByteArray()))
            assertTrue(review.warnings.none { it.level == Danger.DANGER }, text)
        }
    }

    @Test
    fun aBinaryPersonalMessageOfAnotherLengthIsNotADanger() {
        for (size in listOf(31, 33)) {
            val review = reviewOf(personalParsed(ByteArray(size) { 0xff.toByte() }))
            assertTrue(review.warnings.none { it.level == Danger.DANGER }, "$size bytes")
        }
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

    /** A line break or a tab in the origin could draw rows that look like further header rows, so a request that holds one is refused. */
    @Test
    fun aRequestWhoseOriginHasALineBreakOrATabIsRefused() {
        for (origin in listOf("dapp.example\n\nType\nSign-in message", "dapp\tx", "dapp\u0085x", "dapp\u2028x", "dapp\u2029x")) {
            val refusal = refusalOf(personalParsed("hi".toByteArray(), origin = origin))

            assertEquals("The request names an origin with a character Lensly cannot show.", refusal.reason)
        }
        assertEquals("dapp.example", reviewOf(personalParsed("hi".toByteArray(), origin = "dapp.example")).sections.flatMap { it.rows }
            .first { it.label == "Claimed by Request (Not Verified)" }.value.text())
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

private const val AAVE_POOL = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2"
private const val UR_V2 = "0x66a9893cc07d91d95644aedd05d03f95e1dba8af"
/** A transaction names its contract in checksum form. */
private val UR_V2_TX = Keys.toChecksumAddress(UR_V2)
private const val UR_MSG_SENDER = "0x0000000000000000000000000000000000000001"
private const val UR_ADDRESS_THIS = "0x0000000000000000000000000000000000000002"
private const val WETH_ADDRESS = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"
private const val ACK_STEP_PAYEE = "I understand this call pays an account other than mine."
private const val ACK_ROUTER_LEFTOVER = "I understand this call leaves ETH or tokens in the router, where anyone can take them."

/** The approved Universal Router V2 `execute(bytes,bytes[],uint256)` on Ethereum. */
private fun urEntry(): WhitelistEntry {
    val signature = "execute(bytes,bytes[],uint256)"
    return WhitelistEntry(
        chainId = 1,
        contract = UR_V2,
        selector = WhitelistEntry.selectorOf(signature),
        signature = signature,
        meaning = "Runs the router commands in order.",
        params = listOf(WhitelistParam("commands", "bytes"), WhitelistParam("inputs", "bytes[]"), WhitelistParam("deadline", "uint256")),
        upgradeable = false,
    )
}

/** The calldata of `execute(commands, inputs, 1)`. */
private fun urExecute(commands: ByteArray, inputs: List<ByteArray>): ByteArray {
    val params = urEntry().params
    val decoded = listOf(
        DecodedParam(params[0], AbiValue.Dynamic(commands.toHex())),
        DecodedParam(params[1], AbiValue.Items(inputs.map { AbiValue.Dynamic(it.toHex()) })),
        DecodedParam(params[2], AbiValue.Word("00".repeat(31) + "01")),
    )
    return Numeric.hexStringToByteArray(urEntry().selector) + AbiDecoder.encode(decoded)!!
}

/** WRAP_ETH of 1 ETH to [recipient]. */
private fun urWrapInput(recipient: String): ByteArray = word(BigInteger(recipient.removePrefix("0x"), 16)) + word(ONE_ETH)

/** V3_SWAP_EXACT_IN of 1 WETH for at least 3,000 USDC through the 0.05% pool, with the output sent to [recipient]. */
private fun urSwapInput(recipient: String, payerIsUser: Boolean = false): ByteArray {
    val path = Numeric.hexStringToByteArray(WETH_ADDRESS) + byteArrayOf(0, 1, 0xf4.toByte()) + Numeric.hexStringToByteArray(USDC_LOWER)
    val padded = path + ByteArray((32 - path.size % 32) % 32)
    return word(BigInteger(recipient.removePrefix("0x"), 16)) + word(ONE_ETH) + word(BigInteger.valueOf(3_000_000_000)) +
        word(BigInteger.valueOf(5 * 32)) + word(if (payerIsUser) BigInteger.ONE else BigInteger.ZERO) +
        word(BigInteger.valueOf(path.size.toLong())) + padded
}

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

private const val LIDO = "0xae7ab96520de3a18e5e111b5eaab095312d7fe84"
private val ONE_ETH = BigInteger("1000000000000000000")
private val TWO_ETH = BigInteger("2000000000000000000")
private val THREE_ETH = BigInteger("3000000000000000000")

/** The approved Lido `submit(address)`, whose step line names the amount the call sends. */
private fun lidoEntry(): WhitelistEntry {
    val signature = "submit(address)"
    return WhitelistEntry(
        chainId = 1,
        contract = LIDO,
        selector = WhitelistEntry.selectorOf(signature),
        signature = signature,
        meaning = "Stakes the ETH the call sends.",
        params = listOf(WhitelistParam("referral", "address")),
        upgradeable = false,
    )
}

/** A Multicall3 batch of two Lido `submit` calls. [first] and [second] are their values, or null for a shape that has none. */
private fun lidoBatch(shape: MulticallShape, first: BigInteger?, second: BigInteger?): ByteArray {
    val calldata = Numeric.hexStringToByteArray(lidoEntry().selector) + word(BigInteger.ZERO)
    val calls = listOf(first, second).map {
        InnerCall(target = LIDO, allowFailure = false, value = it, calldata = calldata, call = null, nested = null)
    }
    return encodeMulticall(shape, deadline = null, calls = calls)!!
}

/** `aggregate3Value` with one call: a transfer of nothing on USDC, which the registry knows, that sends [value] along (1 ETH by default). */
private fun aggregate3ValueTransfer(allowFailure: Boolean = false, value: BigInteger = ONE_ETH): ByteArray {
    val transfer = ContractCall.Transfer("0x1111111111111111111111111111111111111111", BigInteger.ZERO).encode()!!
    val call = InnerCall(USDC, allowFailure = allowFailure, value = value, calldata = transfer, call = ContractCall.decode(transfer), nested = null)
    return encodeMulticall(MulticallShape.AGGREGATE3_VALUE, deadline = null, calls = listOf(call))!!
}

/** An `aggregate3` call to Multicall3 whose one call is an `aggregate3` of [nestedOn], holding an unlimited USDC approval. */
private fun nestedMulticall3Batch(nestedOn: String): ByteArray {
    val approveBytes = ContractCall.Approve(SPENDER, MAX_UINT256).encode()!!
    val approve = InnerCall(USDC, allowFailure = false, value = null, calldata = approveBytes, call = ContractCall.decode(approveBytes), nested = null)
    val nestedBytes = encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(approve))!!
    val outer = InnerCall(
        target = nestedOn,
        allowFailure = false,
        value = null,
        calldata = nestedBytes,
        call = null,
        nested = Multicall(deadline = null, calls = listOf(approve)),
    )
    return encodeMulticall(MulticallShape.AGGREGATE3, deadline = null, calls = listOf(outer))!!
}
