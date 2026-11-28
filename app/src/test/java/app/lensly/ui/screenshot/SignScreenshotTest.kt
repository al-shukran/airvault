package app.lensly.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.airvault.abi.AbiDecoder
import app.airvault.abi.CallDecode
import app.airvault.abi.WhitelistParam
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.SolanaTokenList
import app.airvault.chains.solana.signer.SolanaWarning
import app.airvault.chains.solana.signer.solanaWarningText
import app.airvault.chains.solana.signer.toReview
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SectionStyle
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.UNKNOWN_REQUEST
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.WalletApp
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.SolanaSplFixtures
import app.lensly.app.sign.WalletView
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.registry.AbiRows
import app.lensly.core.registry.CallSteps
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.NetworkKind
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.framesOf
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.flow.SignFlow
import app.lensly.ui.flow.SignFlowPorts
import app.lensly.ui.flow.SignStep
import app.lensly.ui.sign.SAMPLE_SIGNER
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.ReviewScreen
import app.lensly.ui.sign.undecodedCallRows
import app.lensly.ui.theme.LumenTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Review and refusal states of the sign flow. The reviews use [snapshotSettled], because whether APPROVE is on depends on
 * measured sizes that take a few frames to settle. The native and outside-registry reviews open at the top, so APPROVE is off there.
 */
private const val SWAP_CONTRACT = "0x5555555555555555555555555555555555555555"
private const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
private const val SWAP_SIGNATURE = "swapExactTokensForTokens(uint256,uint256,address)"
private const val SWAP_RECIPIENT = "0xf278cf59f82edcf871d630f28ecc8056f25c1cdb"
private const val SWAP_MEANING = "Swap an exact amount of one token for another, along the route in the call."

/** The arguments of [SWAP_SIGNATURE], as the whitelist entry declares them. */
private val SWAP_PARAMS = listOf(
    WhitelistParam("amountIn", "uint256", tokenRef = true),
    WhitelistParam("amountOutMin", "uint256"),
    WhitelistParam("to", "address"),
)

/** A real calldata for the swap: the selector, then one full word per argument. */
private val SWAP_CALLDATA = Numeric.hexStringToByteArray(
    "0x6e17e9f6" +
        Numeric.toHexStringNoPrefixZeroPadded(BigInteger.ONE, 64) +
        Numeric.toHexStringNoPrefixZeroPadded(BigInteger.TEN, 64) +
        Numeric.toHexStringNoPrefixZeroPadded(BigInteger(SWAP_RECIPIENT.removePrefix("0x"), 16), 64),
)

/** What the decoder reads from [SWAP_CALLDATA], so the review lists the values the bytes hold and not typed-in ones. */
private val SWAP_DECODED = (AbiDecoder.decode(SWAP_CALLDATA, SWAP_PARAMS) as CallDecode.Ok).params

/** The Arguments rows of [SWAP_DECODED], as the sign flow shows them. */
private val SWAP_ROWS = SWAP_DECODED.flatMap { AbiRows.of(it) }.map { (label, value) -> ReviewRow(label, DisplayValue.Text(value), null) }

/** A call Lensly cannot decode that the whitelist approves, on the sample network. [proxy] is set for a proxy under Warn and confirm. */
private const val HYPE_SYSTEM = "0x2222222222222222222222222222222222222222"
private val HYPEREVM_CHAIN = ChainId("eip155:999")
private val ARBITRUM_CHAIN = ChainId("eip155:42161")

/** A HyperEVM native transfer of HYPE to HyperCore: its top label names the move, and its rows show the system address as the recipient. */
private val hyperCoreMoveSample = Review(
    title = "Move to HyperCore",
    chain = HYPEREVM_CHAIN,
    network = NetworkLabel(name = "HyperEVM", testnet = false, detail = "Chain ID 999"),
    summary = listOf(SummaryPart.Send("1", "HYPE", SAMPLE_FROM, HYPE_SYSTEM)),
    facts = listOf(ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(7)), null)),
    sections = listOf(
        ReviewSection(
            "Transaction",
            listOf(
                ReviewRow("From", DisplayValue.Address(HYPEREVM_CHAIN, SAMPLE_FROM), null),
                ReviewRow("To", DisplayValue.Address(HYPEREVM_CHAIN, HYPE_SYSTEM), null),
                ReviewRow("Value", DisplayValue.Text("1 HYPE"), null),
            ),
        ),
    ),
    warnings = emptyList(),
    coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
)

/** A SpotSend of HYPE from HyperCore to HyperEVM, signed on the Hyperliquid domain: its top label names the move, and every field is shown. */
private val hyperEvmMoveSample = Review(
    title = "Move to HyperEVM",
    chain = ARBITRUM_CHAIN,
    network = NetworkLabel(name = "Arbitrum One", testnet = false, detail = "Chain ID 42161"),
    summary = listOf(SummaryPart.Text("Signs typed data of type HyperliquidTransaction:SpotSend.")),
    facts = emptyList(),
    sections = listOf(
        ReviewSection(
            "Request",
            listOf(
                ReviewRow(
                    "Off-Chain Signature for Hyperliquid",
                    DisplayValue.Text("The domain matches Hyperliquid's published message format. Lensly cannot tell what the requester does with the signature."),
                    null,
                ),
            ),
        ),
        ReviewSection(
            "Message",
            listOf(
                ReviewRow("destination (string)", DisplayValue.Text(HYPE_SYSTEM), null),
                ReviewRow("token (string)", DisplayValue.Text("HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"), null),
                ReviewRow("amount (string)", DisplayValue.Text("1"), null),
            ),
        ),
    ),
    warnings = listOf(
        Warning(
            Danger.CAUTION,
            "evm.offchain",
            "This signature is for Hyperliquid. It can authorize actions in your Hyperliquid account. Check the message before you sign.",
            acknowledge = "I understand this signature goes to Hyperliquid, and Lensly cannot tell what it does.",
        ),
    ),
    coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
)

private fun whitelistedSample(proxy: ProxyBinding? = null): Review = sampleNativeReview.copy(
    title = "swapExactTokensForTokens",
    summary = listOf(
        SummaryPart.Text("Calls swapExactTokensForTokens on "),
        SummaryPart.Counterparty(SWAP_CONTRACT),
        SummaryPart.Text(", which the signed list approves."),
    ),
    sections = listOf(
        transactionSection(to = "Contract", target = SWAP_CONTRACT, function = SWAP_SIGNATURE),
        ReviewSection(
            "Arguments",
            buildList {
                add(ReviewRow("Approved Meaning", DisplayValue.Text(SWAP_MEANING), null))
                addAll(SWAP_ROWS)
                if (proxy != null) add(ReviewRow("Implementation", DisplayValue.Address(MONAD_CHAIN, IMPLEMENTATION), null))
            },
        ),
    ),
    warnings = buildList {
        add(approvedCallWarning(SWAP_MEANING))
        if (proxy != null) add(upgradeWarning())
    },
)

/** A multicall with its calls listed level by level: an approval, and a whitelisted swap one level down under its warning. */
private fun nestedSample(): Review = sampleNativeReview.copy(
    title = "multicall",
    summary = listOf(SummaryPart.Text("Runs 3 calls in one transaction.")),
    sections = listOf(
        transactionSection(
            to = "Contract",
            target = SWAP_CONTRACT,
            function = "multicall(uint256,bytes[])",
            extra = listOf(ReviewRow("deadline (uint256)", DisplayValue.Number(BigInteger.valueOf(1_700_000_000L)), null)),
        ),
        ReviewSection(
            "Calls",
            buildList {
                add(ReviewRow("Call 1", DisplayValue.Text("approve"), null))
                add(ReviewRow("spender", DisplayValue.Text(SWAP_RECIPIENT), null, depth = 1))
                add(ReviewRow("value", DisplayValue.Text("10"), null, depth = 1))
                add(ReviewRow("Call 2", DisplayValue.Text("aggregate3"), null))
                add(ReviewRow("allowFailure (bool)", DisplayValue.Text("false"), null, depth = 1))
                add(ReviewRow("Call 1", DisplayValue.Text("swapExactTokensForTokens"), null, depth = 1))
                add(ReviewRow("Contract", DisplayValue.Address(MONAD_CHAIN, SWAP_CONTRACT), null, depth = 2))
                add(ReviewRow("Approved Meaning", DisplayValue.Text(SWAP_MEANING), null, depth = 2))
                addAll(SWAP_ROWS.map { it.copy(depth = 2) })
            },
        ),
    ),
    warnings = listOf(approvedCallWarning(SWAP_MEANING)),
)

/**
 * Transaction 0x3bb82bad on Universal Router V1.2: a Permit2 permit of USDC for the router, a V3 swap of 10,000 USDC to WETH, and an unwrap.
 * It is the calldata of the batch the steps describe.
 */
private const val UR_BATCH_CALLDATA =
    "0x3593564c00000000000000000000000000000000000000000000000000000000000000600000000000000000000000" +
    "0000000000000000000000000000000000000000a0000000000000000000000000000000000000000000000000000000" +
    "006ae3e6c300000000000000000000000000000000000000000000000000000000000000030a000c0000000000000000" +
    "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
    "000000000300000000000000000000000000000000000000000000000000000000000000600000000000000000000000" +
    "0000000000000000000000000000000000000001e0000000000000000000000000000000000000000000000000000000" +
    "000000030000000000000000000000000000000000000000000000000000000000000001600000000000000000000000" +
    "00a0b86991c6218b36c1d19d4a2e9eb0ce3606eb48000000000000000000000000ffffffffffffffffffffffffffffff" +
    "ffffffffff000000000000000000000000000000000000000000000000000000006b0b717d0000000000000000000000" +
    "0000000000000000000000000000000000000000110000000000000000000000003fc91a3afd70395cd496c647d5a6cc" +
    "9d4b2b7fad000000000000000000000000000000000000000000000000000000006ae3eb850000000000000000000000" +
    "0000000000000000000000000000000000000000e0000000000000000000000000000000000000000000000000000000" +
    "00000000414d967f4f85ef036df9e1fa459570ebe4bfb4fddc56a75891071c9f667d9210d7620c9dfc9a421233eabf90" +
    "00e697a1fe3b6f701f103eb1cb37a72baa98390eaa1b0000000000000000000000000000000000000000000000000000" +
    "000000000000000000000000000000000000000000000000000000000000000000000001000000000000000000000000" +
    "000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000" +
    "02540be4000000000000000000000000000000000000000000000000003e534782f2f795fd0000000000000000000000" +
    "0000000000000000000000000000000000000000a0000000000000000000000000000000000000000000000000000000" +
    "0000000001000000000000000000000000000000000000000000000000000000000000002ba0b86991c6218b36c1d19d" +
    "4a2e9eb0ce3606eb480001f4c02aaa39b223fe8d0a0e5c4f27ead9083c756cc200000000000000000000000000000000" +
    "000000000000000000000000000000000000000000000000000000000000000000000000400000000000000000000000" +
    "0000000000000000000000000000000000000000010000000000000000000000000000000000000000000000003e5347" +
    "82f2f795fd"

private const val UR_V1_2 = "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"

/** The account that sent the batch, which its steps name as "you". */
private const val UR_SIGNER = "0x0bf93ea5a1fa4ce3dd22c7ffd314462d3869777f"

/** What the batch does, as the whitelist entry states it. */
private const val UR_MEANING = "Uniswap Universal Router: run a batch of swap and transfer commands in one call."

/** The Ethereum network and the tokens the batch's steps name, as the registry holds them. */
private val UR_NETWORK = NetworkEntry(
    chainId = 1L,
    name = "Ethereum",
    nativeSymbol = "ETH",
    nativeDecimals = 18,
    feeModel = FeeModel.L1,
    explorerName = "Etherscan",
    testnet = false,
    icon = "chain_ethereum",
    featured = false, kind = NetworkKind.LAYER_1, parentChainId = null,
)
private val UR_REGISTRY = Registry(
    networks = listOf(UR_NETWORK),
    tokens = listOf(
        TokenEntry(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6, icon = "token_usdc"),
        TokenEntry(1L, "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "WETH", "Wrapped Ether", 18),
    ),
)

/** An account other than [UR_SIGNER], which the steps of the samples below pay, so their lines carry a grouped address. */
private const val OTHER_ACCOUNT = "0x1593d3987a6c4d8b0e2f5a9c7b3e1d4f3ea25343"

/**
 * [UR_BATCH_CALLDATA] with its last step, the unwrap, paying [OTHER_ACCOUNT] and not the sender. The recipient is the first of the two
 * words that end the input, and the minimum amount is the second.
 */
private val UR_BATCH_PAYING_OTHER =
    UR_BATCH_CALLDATA.dropLast(128) + OTHER_ACCOUNT.removePrefix("0x").padStart(64, '0') + UR_BATCH_CALLDATA.takeLast(64)

/** The steps of the batch [calldata], read by the same code the sign flow runs. */
private fun urSteps(calldata: String): CallSteps? = CallSteps.describe(
    UR_REGISTRY,
    1L,
    UR_V1_2,
    "0x3593564c",
    "execute(bytes,bytes[],uint256)",
    UR_SIGNER,
    BigInteger.ZERO,
    urDecoded(calldata),
)

/** The arguments of `execute(bytes,bytes[],uint256)`, decoded from [calldata] as the sandbox decodes them. */
private fun urDecoded(calldata: String) = (
    AbiDecoder.decode(
        Numeric.hexStringToByteArray(calldata),
        listOf(WhitelistParam("commands", "bytes"), WhitelistParam("inputs", "bytes[]"), WhitelistParam("deadline", "uint256")),
    ) as CallDecode.Ok
    ).params

/** A Universal Router batch the owner signs: its steps, the approval warning they raise, and the arguments behind Details. */
internal fun urBatchSample(calldata: String = UR_BATCH_CALLDATA): Review {
    val chain = ChainId("eip155:1")
    val steps = urSteps(calldata)
    return Review(
        title = "execute",
        chain = chain,
        network = NetworkLabel(name = "Ethereum", testnet = false, detail = "Chain ID 1"),
        summary = listOf(
            SummaryPart.Text("Calls execute on "),
            SummaryPart.Counterparty(UR_V1_2),
            SummaryPart.Text(", which the signed list approves."),
        ),
        facts = sampleNativeReview.facts,
        sections = listOf(
            transactionSection(to = "Contract", target = UR_V1_2, function = "execute(bytes,bytes[],uint256)", chain = chain, signer = UR_SIGNER),
            ReviewSection(
                "Arguments",
                listOf(ReviewRow("Approved Meaning", DisplayValue.Text(UR_MEANING), null)) +
                    urDecoded(calldata).flatMap { AbiRows.of(it) }.map { (label, value) -> ReviewRow(label, DisplayValue.Text(value), null) },
            ),
            ReviewSection("Steps", steps?.lines.orEmpty().map { ReviewRow(it, null, null) }, SectionStyle.Steps),
        ),
        warnings = buildList {
            add(approvedCallWarning(UR_MEANING))
            steps?.warnings?.forEach { add(Warning(Danger.CAUTION, "evm.steps", it)) }
        },
        coverage = sampleNativeReview.coverage,
    )
}

/** The last step of the batch [calldata], which the screenshots scroll to so every step sits in view above it. */
private fun lastUrStep(calldata: String): String = urSteps(calldata)!!.lines.last()

private const val AAVE_POOL = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2"
private const val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

/** `supply(address,uint256,address,uint16)` of 2,500 USDC to Aave V3 on behalf of [OTHER_ACCOUNT]. */
private val AAVE_SUPPLY_CALLDATA = "0x617ba037" +
    USDC.removePrefix("0x").padStart(64, '0') +
    BigInteger.valueOf(2_500_000_000L).toString(16).padStart(64, '0') +
    OTHER_ACCOUNT.removePrefix("0x").padStart(64, '0') +
    "0".repeat(64)

private val AAVE_SUPPLY_PARAMS = listOf(
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("onBehalfOf", "address"),
    WhitelistParam("referralCode", "uint16"),
)

private val AAVE_SUPPLY_DECODED = (
    AbiDecoder.decode(Numeric.hexStringToByteArray(AAVE_SUPPLY_CALLDATA), AAVE_SUPPLY_PARAMS) as CallDecode.Ok
    ).params

private val AAVE_STEPS = CallSteps.describe(
    UR_REGISTRY,
    1L,
    AAVE_POOL,
    "0x617ba037",
    "supply(address,uint256,address,uint16)",
    UR_SIGNER,
    BigInteger.ZERO,
    AAVE_SUPPLY_DECODED,
)

/** An Aave V3 supply the owner signs for another account: its one step, and the warning that it credits that account. */
internal fun aaveSupplySample(): Review {
    val chain = ChainId("eip155:1")
    return Review(
        title = "supply",
        chain = chain,
        network = NetworkLabel(name = "Ethereum", testnet = false, detail = "Chain ID 1"),
        summary = listOf(
            SummaryPart.Text("Calls supply on "),
            SummaryPart.Counterparty(AAVE_POOL),
            SummaryPart.Text(", which the signed list approves."),
        ),
        facts = sampleNativeReview.facts,
        sections = listOf(
            transactionSection(to = "Contract", target = AAVE_POOL, function = "supply(address,uint256,address,uint16)", chain = chain, signer = UR_SIGNER),
            ReviewSection("Steps", AAVE_STEPS?.lines.orEmpty().map { ReviewRow(it, null, null) }, SectionStyle.Steps),
        ),
        warnings = buildList {
            add(approvedCallWarning("Aave V3: supply an asset to the pool."))
            AAVE_STEPS?.warnings?.forEach { add(Warning(Danger.CAUTION, "evm.steps", it)) }
        },
        coverage = sampleNativeReview.coverage,
    )
}

private val AAVE_SUPPLY_STEP = AAVE_STEPS!!.lines.single()

/** [urBatchSample] for the batch whose unwrap pays another account. */
internal fun urBatchPayingOtherSample(): Review = urBatchSample(UR_BATCH_PAYING_OTHER)

/** A native transfer whose one warning has a check to tick, and few facts, so the review fits on screen with its hold off. */
private val shortWarningSample = sampleNativeReview.copy(
    facts = listOf(ReviewRow("Max Fee", DisplayValue.Text("52.5 gwei"), null)),
    warnings = listOf(
        Warning(
            Danger.CAUTION,
            "evm.registry",
            "This network is not in Lensly's registry, so Lensly cannot check its coin or decimals.",
            acknowledge = "I have checked the chain ID and the contract.",
        ),
    ),
)

/** The Monad Testnet chain of the native sample, which the whitelisted and nested samples share. */
private val MONAD_CHAIN = sampleNativeReview.chain

/**
 * The Transaction section of a sample call: the signer, the [to] row, the function, the type and the nonce. [extra] rows sit before the type.
 */
private fun transactionSection(
    to: String,
    target: String,
    function: String,
    chain: ChainId = MONAD_CHAIN,
    signer: String = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F",
    extra: List<ReviewRow> = emptyList(),
): ReviewSection = ReviewSection(
    "Transaction",
    buildList {
        add(ReviewRow("From", DisplayValue.Address(chain, signer), null))
        add(ReviewRow(to, DisplayValue.Address(chain, target), null))
        add(ReviewRow("Function", DisplayValue.Text(function), null))
        addAll(extra)
        add(ReviewRow("Transaction Type", DisplayValue.Text("EIP-1559"), null))
        add(ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(42)), null))
    },
)

/** The warning on a call the whitelist approves, with the check the owner ticks for it. */
private fun approvedCallWarning(meaning: String): Warning = Warning(
    Danger.CAUTION,
    "evm.whitelisted-call",
    "Lensly cannot read this call. The signed list approves it: $meaning",
    acknowledge = "I have read what this call is approved to do.",
)

/** The warning on a whitelisted proxy under Warn and confirm, with the check the owner ticks for it. */
private fun upgradeWarning(): Warning = Warning(
    Danger.DANGER,
    "evm.proxy",
    "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is still the version the signed list approves.",
    acknowledge = "I accept that this proxy's code cannot be checked offline.",
)

/**
 * [snapshotSettled] for a review that scrolls first: the same 720 x 1600 px window at 280 dpi, then a few frames so
 * measured sizes settle, then the review scrolls to the node whose text is [label]. The review is a plain vertical
 * scroll, which Compose's performScrollToNode does not support, so this scrolls the node itself into view.
 */
private fun ComposeContentTestRule.snapshotScrolled(name: String, label: String, content: @Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(REVIEW_DENSITY)) {
            LumenTheme {
                Box(
                    modifier = Modifier
                        .testTag(REVIEW_TAG)
                        .requiredSize(
                            width = (720f / REVIEW_DENSITY).dp,
                            height = (1600f / REVIEW_DENSITY).dp,
                        )
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    content()
                }
            }
        }
    }
    repeat(REVIEW_FRAMES) {
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
    // performScrollTo applies the scroll through the frame clock, so the clock must advance while it runs.
    mainClock.autoAdvance = true
    onNodeWithText(label).performScrollTo()
    waitForIdle()
    mainClock.autoAdvance = false
    repeat(REVIEW_FRAMES) {
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
    waitForIdle()
    onNodeWithTag(REVIEW_TAG).captureRoboImage(filePath = "$SCREENSHOT_DIR/$name.png")
}

private const val REVIEW_TAG = "lensly-scrolled-review"
private const val REVIEW_DENSITY = 1.75f

/** Frames to run before a settled capture, the same count [snapshotSettled] runs. */
private const val REVIEW_FRAMES = 6

/** The token list that names USDC as the bundled list does, so the Solana reviews name the token as the app shows it. */
private val SOLANA_USDC_TOKENS = SolanaTokenList.parse(
    """{"tokens": [{"mint": "${SolanaSplFixtures.USDC}", "symbol": "USDC", "name": "USD Coin", "decimals": 6}]}""",
)

/** The Solana review of the SPL vector [name] for account 0, whose fee payer is that account, checked against [tokens]. */
private fun solanaReviewOf(name: String, tokens: SolanaTokenList): SolanaRequestReview {
    val request = SolSignRequest(
        requestId = null,
        signData = SolanaSplFixtures[name],
        derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
        sourceFingerprint = null,
        address = null,
        origin = null,
        signType = SolSignType.TRANSACTION,
    )
    val checked = SolanaRequestCheck.check(request, listOf(Base58.decode(SolanaSplFixtures.PAYER)), tokens)
    return (checked as SolanaRequestCheck.Result.Ok).review
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewNativeTransfer() = compose.snapshotSettled("tx_review_native") {
        EvmReviewScreen(review = sampleNativeReview)
    }

    @Test
    fun reviewMoveToHyperCore() = compose.snapshotSettled("tx_review_move_to_hypercore") {
        EvmReviewScreen(review = hyperCoreMoveSample)
    }

    @Test
    fun reviewMoveToHyperEvm() = compose.snapshotSettled("tx_review_move_to_hyperevm") {
        EvmReviewScreen(review = hyperEvmMoveSample)
    }

    @Test
    fun reviewSolanaSplTransferChecked() = compose.snapshotScrolled("solana_spl_transfer_checked", label = "Priority Fee") {
        ReviewScreen(review = solanaReviewOf("spl-usdc-first-transfer", SOLANA_USDC_TOKENS).toReview(), signer = SAMPLE_SIGNER, addressNotes = emptyMap(), addressChain = SOLANA_FAMILY, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewSolanaUnknownProgram() = compose.snapshotScrolled(
        "solana_spl_unknown_program",
        label = solanaWarningText(SolanaWarning.UNKNOWN_PROGRAM),
    ) {
        ReviewScreen(review = solanaReviewOf("spl-unknown-program-usdc", SOLANA_USDC_TOKENS).toReview(), signer = SAMPLE_SIGNER, addressNotes = emptyMap(), addressChain = SOLANA_FAMILY, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewSolanaPlainTransfer() = compose.snapshotScrolled(
        "solana_plain_transfer",
        label = solanaWarningText(SolanaWarning.DECIMALS_UNKNOWN),
    ) {
        ReviewScreen(review = solanaReviewOf("spl-transfer-plain", SOLANA_USDC_TOKENS).toReview(), signer = SAMPLE_SIGNER, addressNotes = emptyMap(), addressChain = SOLANA_FAMILY, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWhitelistedCall() = compose.snapshotSettled("tx_review_whitelisted") {
        EvmReviewScreen(review = whitelistedSample())
    }

    @Test
    fun reviewWhitelistedProxyUnderWarnAndConfirm() = compose.snapshotSettled("tx_review_whitelisted_proxy") {
        EvmReviewScreen(review = whitelistedSample(ProxyBinding(implementationAddress = IMPLEMENTATION)))
    }

    @Test
    fun reviewWhitelistedParams() = compose.snapshotScrolled("tx_review_whitelisted_params", "to (address)") {
        // The arguments are scrolled to their last row, which leaves every argument in view above it.
        EvmReviewScreen(review = whitelistedSample())
    }

    @Test
    fun refusedProxyUnderBindToImplementation() = compose.snapshot("sign_refused_proxy") {
        SignRefusedScreen(
            reason = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is still the approved " +
                "version, so your Proxy Policy refuses it.",
            onDone = {},
            networks = EVM_NETWORKS,
            rows = undecodedCallRows(
                target = SWAP_CONTRACT,
                networkName = "Monad Testnet",
                chainId = 10143L,
                value = "0 MON",
                selector = "7acb7757",
                words = listOf("0".repeat(63) + "a"),
                proxy = ProxyBinding(implementationAddress = IMPLEMENTATION),
            ),
        )
    }

    @Test
    fun reviewUniversalRouterBatchShowsItsSteps() = compose.snapshotScrolled(
        "tx_review_ur_steps",
        // Scrolling to the last step brings all three steps into view above it, and the arguments sit above them in the review.
        lastUrStep(UR_BATCH_CALLDATA),
    ) {
        EvmReviewScreen(review = urBatchSample())
    }

    @Test
    fun reviewUniversalRouterBatchPayingAnotherAccount() = compose.snapshotScrolled(
        "tx_review_ur_steps_other",
        lastUrStep(UR_BATCH_PAYING_OTHER),
    ) {
        EvmReviewScreen(review = urBatchPayingOtherSample())
    }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun reviewUniversalRouterBatchPayingAnotherAccountNight() = compose.snapshotScrolled(
        "tx_review_ur_steps_other_night",
        lastUrStep(UR_BATCH_PAYING_OTHER),
    ) {
        EvmReviewScreen(review = urBatchPayingOtherSample())
    }

    @Test
    fun reviewAaveSupplyForAnotherAccount() = compose.snapshotScrolled("tx_review_aave_supply", AAVE_SUPPLY_STEP) {
        EvmReviewScreen(review = aaveSupplySample())
    }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun reviewAaveSupplyForAnotherAccountNight() = compose.snapshotScrolled("tx_review_aave_supply_night", AAVE_SUPPLY_STEP) {
        EvmReviewScreen(review = aaveSupplySample())
    }

    @Test
    fun reviewNestedCalls() = compose.snapshotScrolled("tx_review_nested", "Approved Meaning") {
        // The review is scrolled to the whitelisted call inside the batch, so the calls above it sit in view, indented by level.
        EvmReviewScreen(review = nestedSample())
    }

    @Test
    fun reviewOutsideRegistry() = compose.snapshotSettled("tx_review_outside_registry") {
        EvmReviewScreen(review = sampleUnknownChainReview)
    }

    @Test
    fun reviewPersonalMessage() = compose.snapshotSettled("tx_review_message") {
        EvmReviewScreen(review = sampleMessageReview)
    }

    @Test
    fun reviewWithACheckOwedIsBlockedUntilItIsTicked() = compose.snapshotSettled("tx_review_blocked") {
        // The review fits on screen, yet Hold to sign is off until the check under its warning is ticked.
        EvmReviewScreen(review = shortWarningSample)
    }

    @Test
    fun refused() = compose.snapshot("sign_refused") {
        SignRefusedScreen(
            reason = "Chain ID mismatch. The request targets chain 1, but this vault is set to chain 10143.",
            onDone = {},
        )
    }

    /** A request of a type no installed app reads, as a newer Lumio sends one. The sign flow shows its plain sentence. */
    @Test
    fun refusedUnknownRequest() = compose.snapshotUnknownRequestRefusal("sign_refused_unknown_request")

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun refusedUnknownRequestNight() = compose.snapshotUnknownRequestRefusal("sign_refused_unknown_request_night")
}

/** A UR type that no app of this build reads, as a request from a newer Lumio carries. */
private const val FUTURE_UR_TYPE = "future-sign-request"

/** The reason the sign flow refuses a scanned request of [FUTURE_UR_TYPE]: the flow reads the scan as it reads a scanned code. */
private fun unknownRequestRefusal(): String = runBlocking {
    val flow = SignFlow(ScanOnlyPorts, walletId = null)
    flow.onScanned(framesOf(UR(FUTURE_UR_TYPE, byteArrayOf(0xA0.toByte())), DEFAULT_MAX_FRAGMENT).first())
    assertIs<SignStep.Refused>(flow.state.value).reason
}

/** Renders the refusal screen of [unknownRequestRefusal], with the reason the flow gave. */
private fun ComposeContentTestRule.snapshotUnknownRequestRefusal(name: String) {
    val reason = unknownRequestRefusal()
    snapshot(name) { SignRefusedScreen(reason = reason, onDone = {}) }
}

/** The sign flow's ports for a request refused on its scan. The Parser Sandbox reads the scan, and a refused scan reaches no other call. */
private object ScanOnlyPorts : SignFlowPorts {
    override suspend fun wallets(): List<WalletView> = error("A refused scan reads no wallet")
    override suspend fun scan(parts: List<String>): ScanReply = inProcessSandboxParser.scan(parts)
    override suspend fun checkPassword(password: CharArray): PasswordCheck = error("A refused scan checks no password")
    override suspend fun isInstalled(walletId: String, app: WalletApp): Boolean = true
    override suspend fun canInstall(walletId: String, app: WalletApp): Boolean = true

    override suspend fun appPrepare(walletId: String, request: AppRequest): AppPrepared = error("A refused scan is not reviewed")
    override suspend fun appSign(ready: AppPrepared.Ready, approval: Approval): List<String> = error("A refused scan signs nothing")
    override suspend fun recordApp(entry: AppReviewEntry): Unit = error("A refused scan records nothing")
}
