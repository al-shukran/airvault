package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import app.airvault.core.model.ChainId
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.app.notesOf
import app.lensly.app.sign.algorandFacts
import app.lensly.app.sign.algorandRows
import app.lensly.app.sign.algorandSentence
import app.lensly.app.sign.algorandTitle
import app.lensly.app.sign.algorandWarningText
import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.algorand.AlgorandWarning
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * The Algorand review, the counterpart of [SolanaReviewScreen]. The header names the network in full and the signing account, its wallet
 * and its address. One sentence says what the transaction does, with its fee under it. A close-to warning sits in a banner at the error
 * tone. An unknown-asset warning and a group warning sit at the warning tone. Every detail is shown on first render. The request is signed
 * only after it is read to its end. REJECT is always enabled.
 */
@Composable
fun AlgorandReviewScreen(
    review: AlgorandRequestReview,
    signer: SigningAccount,
    addressNotes: Map<String, AddressNotes>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens an address of the review in the Address Book, with its chain. Null leaves the address rows without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val open = addressTapOn(ALGORAND_FAMILY, onOpenAddress)
    // A different request starts unread, so nothing carries over from the last one.
    key(review) {
        val read = rememberReviewRead(review)
        LenslyScreen(
            title = algorandTitle(review),
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                ReviewActions(
                    hint = readHint(read),
                    approvable = read.read,
                    onApprove = onApprove,
                    onReject = onReject,
                )
            },
        ) {
            ReviewBody(read) {
                ReviewHeader(
                    network = review.network.displayName,
                    testnet = review.network.testnet,
                    chainDetail = "Genesis ID ${review.network.genesisId}",
                    account = signer.name,
                    wallet = signer.walletName,
                    address = signer.address,
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
                ReviewSection(title = "Transaction") {
                    algorandRows(review).forEach { (label, value) ->
                        ReviewField(label = label, value = value, notes = addressNotes.notesOf(value), onOpen = open)
                    }
                }
            }
        }
    }
}

/** The tone of a warning's banner: a close-to warning is an error, and an unknown asset or a group is only a warning. */
private fun algorandWarningTone(warning: AlgorandWarning): BannerTone = when (warning) {
    AlgorandWarning.CLOSE_TO -> BannerTone.Error
    AlgorandWarning.UNKNOWN_ASSET -> BannerTone.Warning
    AlgorandWarning.GROUP -> BannerTone.Warning
}
