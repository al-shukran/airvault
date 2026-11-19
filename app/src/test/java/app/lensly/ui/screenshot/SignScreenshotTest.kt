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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.airvault.abi.AbiDecoder
import app.airvault.abi.CallDecode
import app.airvault.abi.WhitelistParam
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.lensly.app.sign.SolanaSplFixtures
import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WhitelistedCall
import app.lensly.app.sign.solanaWarningText
import app.lensly.core.registry.AbiRows
import app.lensly.core.registry.CallSteps
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.solana.SolanaRequestCheck
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaTokenList
import app.lensly.core.solana.SolanaWarning
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.NestedCall
import app.lensly.ui.sign.SAMPLE_SIGNER
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.SolanaReviewScreen
import app.lensly.ui.sign.TxReviewScreen
import app.lensly.ui.theme.LumenTheme
import com.github.takahirom.roborazzi.captureRoboImage
import fr.acinq.bitcoin.Base58
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

/** The review rows of [SWAP_DECODED], as the sign flow shows them. */
private val SWAP_ROWS = SWAP_DECODED.flatMap { AbiRows.of(it) }

/** A call Lensly cannot decode that the whitelist approves, on the sample network. [proxy] is set for a proxy under Warn and confirm. */
private fun whitelistedSample(proxy: ProxyBinding? = null) = sampleNativeReview.copy(
    function = "swapExactTokensForTokens",
    toAddress = SWAP_CONTRACT,
    amount = "",
    symbol = "",
    params = SWAP_ROWS,
    warnings = emptyList(),
    whitelisted = WhitelistedCall(
        signature = SWAP_SIGNATURE,
        meaning = "Swap an exact amount of one token for another, along the route in the call.",
        call = UndecodedCall(
            target = SWAP_CONTRACT,
            networkName = "Monad Testnet",
            chainId = 10143L,
            value = "0 MON",
            selector = "6e17e9f6",
            words = SWAP_CALLDATA.drop(4).toByteArray().toHex().chunked(64),
            proxy = proxy,
        ),
    ),
)

/** A multicall with its calls listed level by level: an approval, and a whitelisted swap one level down under its warning. */
private fun nestedSample() = sampleNativeReview.copy(
    function = "multicall",
    signature = "multicall(uint256,bytes[])",
    toAddress = SWAP_CONTRACT,
    amount = "",
    symbol = "",
    params = listOf("deadline (uint256)" to "1700000000"),
    warnings = emptyList(),
    nested = listOf(
        NestedCall(depth = 1, target = null, function = "approve", params = listOf("spender" to SWAP_RECIPIENT, "value" to "10")),
        NestedCall(depth = 1, target = null, function = "aggregate3", params = listOf("allowFailure (bool)" to "false")),
        NestedCall(
            depth = 2,
            target = SWAP_CONTRACT,
            function = "swapExactTokensForTokens",
            params = SWAP_ROWS,
            whitelisted = WhitelistedCall(
                signature = SWAP_SIGNATURE,
                meaning = "Swap an exact amount of one token for another, along the route in the call.",
                call = UndecodedCall(
                    target = SWAP_CONTRACT,
                    networkName = "Monad Testnet",
                    chainId = 10143L,
                    value = "0 MON",
                    selector = "6e17e9f6",
                    words = SWAP_CALLDATA.drop(4).toByteArray().toHex().chunked(64),
                    proxy = null,
                ),
            ),
        ),
    ),
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
)
private val UR_REGISTRY = Registry(
    networks = listOf(UR_NETWORK),
    tokens = listOf(
        TokenEntry(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6, icon = "token_usdc"),
        TokenEntry(1L, "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "WETH", "Wrapped Ether", 18),
    ),
)

/** The arguments of `execute(bytes,bytes[],uint256)`, decoded from [UR_BATCH_CALLDATA] as the sandbox decodes them. */
private val UR_DECODED = (
    AbiDecoder.decode(
        Numeric.hexStringToByteArray(UR_BATCH_CALLDATA),
        listOf(WhitelistParam("commands", "bytes"), WhitelistParam("inputs", "bytes[]"), WhitelistParam("deadline", "uint256")),
    ) as CallDecode.Ok
    ).params

/** The steps of the batch, read by the same code the sign flow runs. */
private val UR_STEPS = CallSteps.describe(
    UR_REGISTRY,
    1L,
    UR_V1_2,
    "0x3593564c",
    "execute(bytes,bytes[],uint256)",
    UR_SIGNER,
    BigInteger.ZERO,
    UR_DECODED,
)

/** A Universal Router batch the owner signs: its steps, the approval warning they raise, and the arguments behind Details. */
private fun urBatchSample() = sampleNativeReview.copy(
    networkName = "Ethereum",
    chainId = 1L,
    network = UR_NETWORK,
    function = "execute",
    signature = "execute(bytes,bytes[],uint256)",
    toAddress = UR_V1_2,
    amount = "",
    symbol = "",
    params = UR_DECODED.flatMap { AbiRows.of(it) },
    warnings = UR_STEPS?.warnings.orEmpty(),
    whitelisted = WhitelistedCall(
        signature = "execute(bytes,bytes[],uint256)",
        meaning = "Uniswap Universal Router: run a batch of swap and transfer commands in one call.",
        call = UndecodedCall(
            target = UR_V1_2,
            networkName = "Ethereum",
            chainId = 1L,
            value = "0 ETH",
            selector = "3593564c",
            words = UR_BATCH_CALLDATA.removePrefix("0x").drop(8).chunked(64),
            proxy = null,
        ),
        steps = UR_STEPS,
    ),
)

/** A native transfer with one warning and no check. Short enough to fit on screen, so only the details stand between it and signing. */
private val shortWarningSample = sampleNativeReview.copy(fee = listOf("Max Fee" to "52.5 gwei"), feeNote = null)

private const val REVIEW_TAG = "lensly-scrolled-review"
private const val REVIEW_DENSITY = 1.75f

/** Frames to run before a settled capture, the same count [snapshotSettled] runs. */
private const val REVIEW_FRAMES = 6

/**
 * [snapshotSettled] for a review that scrolls first: the same 720 x 1600 px window at 280 dpi, then a few frames so
 * measured sizes settle, then the review scrolls to the node whose text is [label]. The review is a plain vertical
 * scroll, which Compose's performScrollToNode does not support, so this scrolls the node itself into view.
 */
private fun ComposeContentTestRule.snapshotScrolled(
    name: String,
    label: String,
    toggle: String? = null,
    content: @Composable () -> Unit,
) {
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
    // A folded section named by [toggle] is scrolled to and clicked open before the scroll to [label], so its contents are laid out.
    toggle?.let {
        onNodeWithText(it).performScrollTo().performClick()
        waitForIdle()
    }
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
        TxReviewScreen(review = sampleNativeReview, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewSolanaSplTransferChecked() = compose.snapshotScrolled("solana_spl_transfer_checked", label = "Priority Fee") {
        SolanaReviewScreen(review = solanaReviewOf("spl-usdc-first-transfer", SOLANA_USDC_TOKENS), signer = SAMPLE_SIGNER, addressNotes = emptyMap(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewSolanaUnknownProgram() = compose.snapshotScrolled(
        "solana_spl_unknown_program",
        label = solanaWarningText(SolanaWarning.UNKNOWN_PROGRAM),
    ) {
        SolanaReviewScreen(review = solanaReviewOf("spl-unknown-program-usdc", SOLANA_USDC_TOKENS), signer = SAMPLE_SIGNER, addressNotes = emptyMap(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewSolanaPlainTransfer() = compose.snapshotScrolled(
        "solana_plain_transfer",
        label = solanaWarningText(SolanaWarning.DECIMALS_UNKNOWN),
    ) {
        SolanaReviewScreen(review = solanaReviewOf("spl-transfer-plain", SOLANA_USDC_TOKENS), signer = SAMPLE_SIGNER, addressNotes = emptyMap(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWhitelistedCall() = compose.snapshotSettled("tx_review_whitelisted") {
        TxReviewScreen(review = whitelistedSample(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWhitelistedProxyUnderWarnAndConfirm() = compose.snapshotSettled("tx_review_whitelisted_proxy") {
        TxReviewScreen(
            review = whitelistedSample(ProxyBinding(implementationAddress = IMPLEMENTATION)),
            onApprove = {},
            onReject = {},
        )
    }

    @Test
    fun reviewWhitelistedParams() = compose.snapshotScrolled("tx_review_whitelisted_params", "to (address)", toggle = "Parameters") {
        // The Parameters section is opened first, and its last argument is scrolled to, which leaves every argument in view above it.
        TxReviewScreen(review = whitelistedSample(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWhitelistedRawCalldata() = compose.snapshotScrolled(
        "tx_review_whitelisted_raw",
        "Word 1",
        toggle = "Raw Data",
    ) {
        // The raw data section is opened first. Its first word is then scrolled to, which leaves the value and the selector in view above it.
        TxReviewScreen(review = whitelistedSample(), onApprove = {}, onReject = {})
    }

    @Test
    fun refusedProxyUnderBindToImplementation() = compose.snapshot("sign_refused_proxy") {
        SignRefusedScreen(
            reason = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is still the approved " +
                "version, so your Proxy Policy refuses it.",
            onDone = {},
            call = UndecodedCall(
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
        // Scrolling to the last step brings all three steps into view above it, and the arguments sit folded behind Details below.
        "Unwrap all WETH the router holds → ETH for you (at least 4.491011881166149117 WETH)",
    ) {
        TxReviewScreen(review = urBatchSample(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewNestedCalls() = compose.snapshotScrolled("tx_review_nested", "swapExactTokensForTokens", toggle = "Calls") {
        // The calls are opened, and the review is scrolled to the whitelisted call under them, so the calls above it sit in view, indented by level.
        TxReviewScreen(review = nestedSample(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewOutsideRegistry() = compose.snapshotSettled("tx_review_outside_registry") {
        TxReviewScreen(review = sampleUnknownChainReview, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewPersonalMessage() = compose.snapshotSettled("tx_review_message") {
        TxReviewScreen(review = sampleMessageReview, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWithAWarningIsBlockedWhileItsDetailsAreFolded() = compose.snapshotSettled("tx_review_blocked") {
        // The review fits on screen, yet Hold to sign is off and says to open the details: the warning is read there.
        TxReviewScreen(review = shortWarningSample, onApprove = {}, onReject = {})
    }

    @Test
    fun refused() = compose.snapshot("sign_refused") {
        SignRefusedScreen(
            reason = "Chain ID mismatch. The request targets chain 1, but this vault is set to chain 10143.",
            onDone = {},
        )
    }
}
