package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import app.lensly.app.sign.algorandFacts
import app.lensly.app.sign.algorandRows
import app.lensly.app.sign.algorandSentence
import app.lensly.app.sign.algorandWarningText
import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.algorand.AlgorandWarning
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * The Algorand review, the counterpart of [SolanaReviewScreen]. The header names the network and the signing account. One sentence says
 * what the transaction does, with its fee under it. A clawback and a close-to warning sit in banners at the error tone. An unknown-asset
 * warning and a group warning sit at the warning tone. A request with a warning is signed only after its details are read to the end. REJECT is always
 * enabled.
 */
@Composable
fun AlgorandReviewScreen(
    review: AlgorandRequestReview,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A different request starts with its details collapsed, so nothing carries over from the last one.
    key(review) {
        val hasWarnings = review.warnings.isNotEmpty()
        val read = rememberReviewRead(review, hasWarnings)
        LenslyScreen(
            title = "Review",
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                ReviewActions(
                    hint = readHint(read, hasWarnings),
                    // A request with a warning is signed only after its details are read to the end.
                    approvable = !hasWarnings || read.read,
                    onApprove = onApprove,
                    onReject = onReject,
                )
            },
        ) {
            ReviewBody(read) {
                ReviewChips(
                    network = review.network.displayName,
                    testnet = review.network.testnet,
                    account = "Account ${review.accountIndex}",
                )
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSummary(
                    sentence = AnnotatedString(algorandSentence(review)),
                    facts = algorandFacts(review).map { (label, value) -> ReviewFact(label = label, value = value) },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                RiskBanner(
                    items = review.warnings.map { RiskItem(text = algorandWarningText(review, it), tone = algorandWarningTone(it)) },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                DetailsSection(title = "Transaction", onOpen = read::openDetails) {
                    algorandRows(review).forEach { (label, value) -> ReviewFactRow(label = label, value = value) }
                }
            }
        }
    }
}

/**
 * The tone of a warning's banner: a clawback and a close-to warning are errors, and an unknown asset or a group is only a warning.
 */
private fun algorandWarningTone(warning: AlgorandWarning): BannerTone = when (warning) {
    AlgorandWarning.CLAWBACK -> BannerTone.Error
    AlgorandWarning.CLOSE_TO -> BannerTone.Error
    AlgorandWarning.UNKNOWN_ASSET -> BannerTone.Warning
    AlgorandWarning.GROUP -> BannerTone.Warning
}
