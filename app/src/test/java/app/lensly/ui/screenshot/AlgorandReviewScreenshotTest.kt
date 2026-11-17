package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.algorand.AlgorandRequestCheck
import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.algorand.AsaCatalog
import app.lensly.core.ur.PathStep
import app.lensly.ui.sign.AlgorandReviewScreen
import app.lensly.ui.sign.SignRefusedScreen
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Algorand review in the asset states the bundled catalog produces: a known asset's transfer on mainnet, an opt-in, an opt-out with
 * its close-to warning, and an asset the catalog does not name. Each review comes from an SDK fixture through the same check the sign
 * flow runs, against the bundled catalog at app/src/main/assets/algorand/asa-catalog.json. A clawback is refused by that check, so its
 * golden is the refusal screen that the sign flow shows for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlgorandReviewScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewMainnetUsdcTransfer() = compose.snapshotSettled("algorand_review_usdc_transfer") {
        AlgorandReviewScreen(review = review(MAINNET_USDC_TRANSFER), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewMainnetUsdcOptIn() = compose.snapshotSettled("algorand_review_opt_in") {
        AlgorandReviewScreen(review = review(MAINNET_USDC_OPT_IN), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewTestnetUsdcOptOut() = compose.snapshotSettled("algorand_review_opt_out") {
        AlgorandReviewScreen(review = review(TESTNET_USDC_OPT_OUT), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewUnknownAsset() = compose.snapshotSettled("algorand_review_unknown_asset") {
        AlgorandReviewScreen(review = review(TESTNET_UNKNOWN_ASSET), onApprove = {}, onReject = {})
    }

    @Test
    fun refusedTestnetUsdcClawback() = compose.snapshot("algorand_review_clawback") {
        SignRefusedScreen(reason = refusalReason(TESTNET_USDC_CLAWBACK), onDone = {})
    }

    /** The review of [hex] for account 0 of a wallet whose account 0 is the sender's key, against the bundled catalog. */
    private fun review(hex: String): AlgorandRequestReview {
        val path = listOf(
            PathStep(44, true),
            PathStep(283, true),
            PathStep(0, true),
            PathStep(0, false),
            PathStep(0, false),
        )
        val catalog = AsaCatalog.parse(File(BUNDLED_CATALOG).readText())
        val checked = AlgorandRequestCheck.check(hexBytes(hex), path, null, listOf(hexBytes(SENDER_PUB)), catalog)
        return (checked as AlgorandRequestCheck.Result.Ok).review
    }

    /** The reason the sign flow refuses [hex] for, against the bundled catalog. */
    private fun refusalReason(hex: String): String {
        val path = listOf(
            PathStep(44, true),
            PathStep(283, true),
            PathStep(0, true),
            PathStep(0, false),
            PathStep(0, false),
        )
        val catalog = AsaCatalog.parse(File(BUNDLED_CATALOG).readText())
        val checked = AlgorandRequestCheck.check(hexBytes(hex), path, null, listOf(hexBytes(SENDER_PUB)), catalog)
        return (checked as AlgorandRequestCheck.Result.Refused).reason
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/** The bundled catalog, read from the module the tests run in. */
private const val BUNDLED_CATALOG = "src/main/assets/algorand/asa-catalog.json"

/** The sender's public key, which is account 0 of the wallet in every review here. */
private const val SENDER_PUB = "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538"

/** The `axfer_mainnet_usdc` vector: 1 USDC on mainnet, to account 1. */
private const val MAINNET_USDC_TRANSFER =
    "8aa461616d74ce000f4240a461726376c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615d" +
        "a3666565cd03e8a26676ce02faf080a367656eac6d61696e6e65742d76312e30a26768c420c061c4d8fc1dbdded2d7604be4" +
        "568e3f6d041987ac37bde4b620b5ab39248adfa26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179" +
        "b9bd1467c72e3c660fc8c2bd2c6538a474797065a56178666572a478616964ce01e1ab70"

/** The `axfer_optin_mainnet_usdc` vector: a mainnet USDC opt-in. */
private const val MAINNET_USDC_OPT_IN =
    "89a461726376c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a3666565cd03e8a26676" +
        "ce02faf080a367656eac6d61696e6e65742d76312e30a26768c420c061c4d8fc1dbdded2d7604be4568e3f6d041987ac37bd" +
        "e4b620b5ab39248adfa26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8" +
        "c2bd2c6538a474797065a56178666572a478616964ce01e1ab70"

/** The `axfer_closeout` vector: a testnet USDC opt-out, the remaining holding to account 1. */
private const val TESTNET_USDC_OPT_OUT =
    "8aa661636c6f7365c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da461726376c420c1" +
        "1dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3666565cd03e8a26676ce02faf080a367656e" +
        "ac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22" +
        "a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a4747970" +
        "65a56178666572a478616964ce009f973d"

/** The `axfer_unknown_asset` vector: 42 units of asset 1234567, which the catalog does not name. */
private const val TESTNET_UNKNOWN_ASSET =
    "8aa461616d742aa461726376c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3666565" +
        "cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb0f" +
        "71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467" +
        "c72e3c660fc8c2bd2c6538a474797065a56178666572a478616964ce0012d687"

/** The `axfer_clawback` vector: 250 testnet USDC units taken from account 2 (asnd) and sent to account 1. */
private const val TESTNET_USDC_CLAWBACK =
    "8ba461616d74ccfaa461726376c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da46173" +
        "6e64c4208d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006da3666565cd03e8a26676ce02faf0" +
        "80a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f" +
        "70e5093a22a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c65" +
        "38a474797065a56178666572a478616964ce009f973d"
