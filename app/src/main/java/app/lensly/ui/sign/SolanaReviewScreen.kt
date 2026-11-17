package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.app.notesOf
import app.lensly.app.sign.SOLANA_NETWORK
import app.lensly.app.sign.solanaFacts
import app.lensly.app.sign.solanaRows
import app.lensly.app.sign.solanaSentence
import app.lensly.app.sign.solanaWarningText
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaWarning
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * The Solana review, the counterpart of [BitcoinReviewScreen]. The header names Solana, since the request names no cluster, and the
 * signing account, its wallet and its address. One sentence says what the request does, with a few facts under it. Every warning
 * sits in one banner, and every detail is shown on first render. The request is signed only after it is read to its end. REJECT is always
 * enabled.
 */
@Composable
fun SolanaReviewScreen(
    review: SolanaRequestReview,
    signer: SigningAccount,
    addressNotes: Map<String, AddressNotes>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A different request starts unread, so nothing carries over from the last one.
    key(review) {
        val read = rememberReviewRead(review)
        LenslyScreen(
            title = "Review",
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
                    network = SOLANA_NETWORK,
                    testnet = false,
                    chainDetail = null,
                    account = signer.name,
                    wallet = signer.walletName,
                    address = signer.address,
                )
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSummary(
                    sentence = AnnotatedString(solanaSentence(review)),
                    facts = solanaFacts(review).map { (label, value) ->
                        ReviewFact(label = label, value = value, mono = label != "Instructions")
                    },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                RiskBanner(items = review.warnings.map { RiskItem(text = solanaWarningText(it), tone = solanaWarningTone(it)) })
                Spacer(Modifier.height(LenslySpacing.md))
                // A signed message has no parsed transaction, so its details are the message. A transaction's details are its instructions.
                ReviewSection(title = if (review.message == null) "Message" else "Instructions") {
                    // Each value is set under its label, so a full base58 address wraps in full rather than squeezing its label.
                    solanaRows(review).forEach { (label, value) -> ReviewField(label = label, value = value, notes = addressNotes.notesOf(value)) }
                }
            }
        }
    }
}

/**
 * An unknown program is an error, since Lensly cannot say what its instruction does. A durable nonce whose authority is not this account
 * is an error too. Every other Solana warning is a warning.
 */
internal fun solanaWarningTone(warning: SolanaWarning): BannerTone = when (warning) {
    SolanaWarning.UNKNOWN_PROGRAM, SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER -> BannerTone.Error
    SolanaWarning.LOOKUP_TABLE, SolanaWarning.UNKNOWN_TOKEN, SolanaWarning.DECIMALS_UNKNOWN -> BannerTone.Warning
}
