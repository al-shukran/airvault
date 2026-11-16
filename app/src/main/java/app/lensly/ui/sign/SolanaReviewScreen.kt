package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import app.lensly.app.sign.SOLANA_NETWORK
import app.lensly.app.sign.solanaFacts
import app.lensly.app.sign.solanaRows
import app.lensly.app.sign.solanaSentence
import app.lensly.app.sign.solanaWarningText
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * The Solana review, the counterpart of [BitcoinReviewScreen]. The header names Solana and the signing account. One sentence says what the
 * request does, with a few facts under it. Every warning sits in one banner. A request with a warning is held until its details are open
 * and read to the end, as the other reviews are. REJECT is always enabled.
 */
@Composable
fun SolanaReviewScreen(
    review: SolanaRequestReview,
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
                ReviewChips(network = SOLANA_NETWORK, testnet = false, account = "Account ${review.accountIndex}")
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSummary(
                    sentence = AnnotatedString(solanaSentence(review)),
                    facts = solanaFacts(review).map { (label, value) ->
                        ReviewFact(label = label, value = value, mono = label != "Instructions")
                    },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                RiskBanner(items = review.warnings.map { RiskItem(text = solanaWarningText(it), tone = BannerTone.Warning) })
                Spacer(Modifier.height(LenslySpacing.md))
                DetailsSection(title = if (review.message == null) "Instructions" else "Message", onOpen = read::openDetails) {
                    solanaRows(review).forEach { (label, value) -> ReviewFactRow(label = label, value = value) }
                }
            }
        }
    }
}
