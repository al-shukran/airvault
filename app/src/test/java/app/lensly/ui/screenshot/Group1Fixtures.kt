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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.lensly.app.AddressNotes
import app.lensly.app.EVM_FAMILY
import app.lensly.app.SigningAccount
import app.lensly.ui.shippedRegistry
import app.lensly.ui.sign.ReviewScreen
import app.lensly.ui.sign.unknownNetworkName
import app.lensly.ui.theme.LumenTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.math.BigInteger

/** A single-part UR, the kind a pairing or signature code carries. */
internal const val SAMPLE_UR =
    "UR:CRYPTO-ACCOUNT/1-1/LPADAXCFAXHDCLAOEYWTJSTYLKSEHDIEUTVDHEKNUECSLNCXLTBBUTZTHDLEHTCMOHGUMWDMCPTDOSVTSSFA"

/** [count] different UR fragments, for an animated code. */
internal fun sampleUrParts(count: Int): List<String> {
    val alphabet = "ABCDEF0123456789"
    return (0 until count).map { part ->
        val body = (0 until 200).map { i -> alphabet[(i * 7 + part * 13) % alphabet.length] }
        "UR:CRYPTO-PSBT/${part + 1}-$count/" + body.joinToString("")
    }
}

internal const val SAMPLE_FROM = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F"
internal const val SAMPLE_TO = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2"

private val MONAD_CHAIN = ChainId("eip155:10143")
private val BASE_CHAIN = ChainId("eip155:8453")
private val UNKNOWN_CHAIN = ChainId("eip155:999999")

/** The account that signs the sample EVM reviews, with the wallet it belongs to. */
internal val SAMPLE_EVM_SIGNER = SigningAccount(name = "Main account", walletName = "Main wallet", address = SAMPLE_FROM)

private fun sampleCoverage() = Coverage(decoded = emptySet(), excluded = emptyMap())

/** A native transfer on a registry chain, with one warning. The review opens at the top, so APPROVE is still off. */
internal val sampleNativeReview = Review(
    title = "Native Transfer",
    chain = MONAD_CHAIN,
    network = NetworkLabel(name = "Monad Testnet", testnet = true, detail = "Chain ID 10143"),
    summary = listOf(SummaryPart.Send("0.01", "MON", SAMPLE_FROM, SAMPLE_TO)),
    facts = listOf(
        ReviewRow("Gas Limit", DisplayValue.Number(BigInteger.valueOf(21_000)), null),
        ReviewRow("Max Fee per Gas", DisplayValue.Text("52.5 gwei"), null),
        ReviewRow("Max Priority Fee", DisplayValue.Text("2 gwei"), null),
        ReviewRow("Max Fee", DisplayValue.Text("0.0011 MON"), null),
    ),
    sections = listOf(
        ReviewSection(
            "Transaction",
            listOf(
                ReviewRow("From", DisplayValue.Address(MONAD_CHAIN, SAMPLE_FROM), null),
                ReviewRow("To", DisplayValue.Address(MONAD_CHAIN, SAMPLE_TO), null),
                ReviewRow("Amount", DisplayValue.Text("0.0100 MON"), null),
                ReviewRow("Transaction Type", DisplayValue.Text("EIP-1559"), null),
                ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(42)), null),
            ),
        ),
    ),
    warnings = listOf(
        Warning(Danger.CAUTION, "evm.first-receipt", "This address has never received a transaction on this network."),
    ),
    coverage = sampleCoverage(),
)

/** A native transfer on a chain outside the registry. The registry block and the unchecked check both show. */
internal val sampleUnknownChainReview = Review(
    title = "Native Transfer",
    chain = UNKNOWN_CHAIN,
    network = NetworkLabel(name = unknownNetworkName(999_999L), testnet = false, detail = null),
    summary = listOf(SummaryPart.Send("1000000000000000000", "base units", SAMPLE_FROM, SAMPLE_TO)),
    facts = listOf(
        ReviewRow("Gas Limit", DisplayValue.Number(BigInteger.valueOf(21_000)), null),
        ReviewRow("Max Fee per Gas", DisplayValue.Text("30 gwei"), null),
        ReviewRow("Max Priority Fee", DisplayValue.Text("1.5 gwei"), null),
        ReviewRow("Max Fee", DisplayValue.Text("630000000000000 base units"), null),
        ReviewRow(
            "Fee Note",
            DisplayValue.Text(
                "≈ 0.00063 if 18 decimals (assumption, not verified). The fee model is unknown, so the total may be higher.",
            ),
            null,
        ),
    ),
    sections = listOf(
        ReviewSection(
            "Transaction",
            listOf(
                ReviewRow("From", DisplayValue.Address(UNKNOWN_CHAIN, SAMPLE_FROM), null),
                ReviewRow("To", DisplayValue.Address(UNKNOWN_CHAIN, SAMPLE_TO), null),
                ReviewRow("Amount", DisplayValue.Text("1000000000000000000 base units"), null),
                ReviewRow("Amount Note", DisplayValue.Text("≈ 1.0 if 18 decimals (assumption, not verified)"), null),
                ReviewRow("Transaction Type", DisplayValue.Text("EIP-1559"), null),
                ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(42)), null),
            ),
        ),
    ),
    warnings = listOf(
        Warning(
            Danger.CAUTION,
            "evm.registry",
            "This network is not in Lensly's registry, so Lensly cannot check its coin or decimals.",
            acknowledge = "I have checked the chain ID and the contract.",
        ),
    ),
    coverage = sampleCoverage(),
)

/** A personal message. It has no recipient, amount, nonce or fee, so the review shows the message text. */
internal val sampleMessageReview = Review(
    title = "Sign Message",
    chain = BASE_CHAIN,
    network = NetworkLabel(name = "Base", testnet = false, detail = "Chain ID 8453"),
    summary = listOf(SummaryPart.Text("Signs a message with this account.")),
    facts = emptyList(),
    sections = listOf(
        ReviewSection(
            "Request",
            listOf(
                ReviewRow("From", DisplayValue.Address(BASE_CHAIN, SAMPLE_FROM), null),
                ReviewRow("Type", DisplayValue.Text("Personal Message"), null),
            ),
        ),
        ReviewSection(
            "Message",
            listOf(
                ReviewRow("Message", DisplayValue.Message("Sign in to Lensly on 11 Nov 2026"), null),
                ReviewRow("Size", DisplayValue.Text("32 characters, 32 bytes"), null),
            ),
        ),
    ),
    warnings = listOf(
        Warning(
            Danger.CAUTION,
            "evm.personal-message",
            "The signature proves you hold this account to whoever checks it, and a site can use it to act for you. " +
                "Sign only a message from a site you trust.",
        ),
    ),
    coverage = sampleCoverage(),
)

/** The networks of the shipped registry, which give each address of an EVM review its network's logo, as the sign route does. */
internal val EVM_NETWORKS by lazy { shippedRegistry().networks() }

/** The EVM review of [review] as the sign route draws it: the sample signer, and the Address Book names in [notes]. */
@Composable
internal fun EvmReviewScreen(review: Review, notes: Map<String, AddressNotes> = emptyMap()) {
    ReviewScreen(
        review = review,
        signer = SAMPLE_EVM_SIGNER,
        addressNotes = notes,
        addressChain = EVM_FAMILY,
        onApprove = {},
        onReject = {},
        networks = EVM_NETWORKS,
    )
}

private const val SETTLED_TAG = "lensly-settled-screen"
private const val SETTLED_DENSITY = 1.75f

/** Frames to run before a settled capture. A measured size reaches the state on a later frame, one step per frame. */
private const val SETTLE_FRAMES = 6

/**
 * [snapshot] for a screen whose state depends on what layout measured, such as a review that checks whether its list
 * fits on screen. The harness stops the test clock, so those states would be captured one step early. This renders at
 * the same 720 x 1600 px and 280 dpi, then runs a few frames before the capture.
 */
internal fun ComposeContentTestRule.snapshotSettled(name: String, content: @Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(SETTLED_DENSITY)) {
            LumenTheme {
                Box(
                    modifier = Modifier
                        .testTag(SETTLED_TAG)
                        .requiredSize(
                            width = (720f / SETTLED_DENSITY).dp,
                            height = (1600f / SETTLED_DENSITY).dp,
                        )
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    content()
                }
            }
        }
    }
    repeat(SETTLE_FRAMES) {
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
    waitForIdle()
    onNodeWithTag(SETTLED_TAG).captureRoboImage(filePath = "$SCREENSHOT_DIR/$name.png")
}
