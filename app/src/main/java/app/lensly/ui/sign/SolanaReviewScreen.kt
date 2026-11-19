package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import app.airvault.core.model.ChainId
import app.lensly.app.AddressNotes
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.SigningAccount
import app.lensly.app.notesOf
import app.lensly.app.sign.SOLANA_NETWORK
import app.lensly.app.sign.solanaFacts
import app.lensly.app.sign.solanaNeedsUnderstanding
import app.lensly.app.sign.solanaNetworkFact
import app.lensly.app.sign.solanaRows
import app.lensly.app.sign.solanaSentence
import app.lensly.app.sign.solanaWarningText
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaWarning
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * The Solana review, the counterpart of the Bitcoin review. The header names Solana, then a network row that names the cluster Lumio
 * states, or says it is not stated, then the signing account, its wallet and its address. One sentence says what the request does, with a
 * few facts under it. Every warning sits in one banner, and every detail is shown on first render. The request is signed only after it is
 * read to its end and, when it has an instruction Lensly does not read, after the owner ticks that Lensly cannot say what it does. REJECT
 * is always enabled.
 */
@Composable
fun SolanaReviewScreen(
    review: SolanaRequestReview,
    signer: SigningAccount,
    addressNotes: Map<String, AddressNotes>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens an address of the review in the Address Book, with its chain. Null leaves the address rows without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val open = addressTapOn(SOLANA_FAMILY, onOpenAddress)
    // A different request starts unread, so nothing carries over from the last one.
    key(review) {
        val read = rememberReviewRead(review)
        // The tick for an instruction Lensly does not read. It starts unticked for each request.
        var understood by remember { mutableStateOf(false) }
        val needsUnderstanding = solanaNeedsUnderstanding(review)
        LenslyScreen(
            title = "Review",
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                ReviewActions(
                    hint = solanaHint(read, needsUnderstanding, understood),
                    approvable = read.read && (!needsUnderstanding || understood),
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
                    networkFact = solanaNetworkFact(review),
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
                RiskBanner(
                    items = review.warnings.map { warning ->
                        RiskItem(
                            text = solanaWarningText(warning),
                            tone = solanaWarningTone(warning),
                            check = if (warning == SolanaWarning.UNKNOWN_PROGRAM) {
                                RiskCheck(label = UNDERSTAND_LABEL, checked = understood, onCheckedChange = { understood = it })
                            } else {
                                null
                            },
                        )
                    },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                // A signed message has no parsed transaction, so its details are the message. A transaction's details are its instructions.
                ReviewSection(title = if (review.message == null) "Message" else "Instructions") {
                    // Each value is set under its label, so a full base58 address wraps in full rather than squeezing its label.
                    solanaRows(review).forEach { (label, value) ->
                        ReviewField(label = label, value = value, notes = addressNotes.notesOf(value), onOpen = open)
                    }
                }
            }
        }
    }
}

/**
 * The line above the buttons: the read of the review first, then the tick an instruction Lensly does not read needs.
 */
private fun solanaHint(read: ReviewRead, needsUnderstanding: Boolean, understood: Boolean): String? {
    readHint(read)?.let { return it }
    return if (needsUnderstanding && !understood) "Tick the checkbox above to sign" else null
}

/**
 * An unknown program is an error, since Lensly cannot say what its instruction does. A durable nonce whose authority is not this account
 * is an error too. A durable nonce that is used is information. Every other Solana warning is a warning.
 */
internal fun solanaWarningTone(warning: SolanaWarning): BannerTone = when (warning) {
    SolanaWarning.UNKNOWN_PROGRAM, SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER -> BannerTone.Error
    SolanaWarning.DURABLE_NONCE -> BannerTone.Info
    SolanaWarning.LOOKUP_TABLE, SolanaWarning.UNKNOWN_TOKEN, SolanaWarning.DECIMALS_UNKNOWN -> BannerTone.Warning
}

/** The text of the tick an unknown instruction needs. Sentence case, in the body face, beside its checkbox. */
private const val UNDERSTAND_LABEL = "I understand Lensly cannot say what this does."
