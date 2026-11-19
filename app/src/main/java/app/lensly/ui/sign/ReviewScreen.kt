package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.text
import app.lensly.app.AddressNotes
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.SigningAccount
import app.lensly.app.notesOf
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * A tick the owner gives before the request can be signed. It sits inside the banner row of the warning whose code is [warningCode], and
 * the request cannot be signed until it is ticked.
 */
data class Acknowledgement(
    val warningCode: String,
    val label: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

/**
 * The one review screen for every chain (spec 3.2). The core builds a [Review] and this screen draws it, so no chain draws its own. The
 * header names the network and the signing account. The summary sentence sits over its facts. Every warning goes in one banner, at the error
 * tone for a danger or a block. Each section lists its rows, all shown on first render. The request is signed only after it is read to its
 * end, only when no warning is a block, and once the [acknowledge] tick, when the review has one, is ticked. REJECT is always enabled.
 */
@Composable
fun ReviewScreen(
    review: Review,
    signer: SigningAccount,
    addressNotes: Map<String, AddressNotes>,
    addressChain: ChainId,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    /** The tick the review's warning needs before the request can be signed. Null when the review needs none. */
    acknowledge: Acknowledgement? = null,
    /** Opens an address of the review in the Address Book, with its chain. Null leaves the address rows without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val open = addressTapOn(addressChain, onOpenAddress)
    val bitcoin = addressChain.namespace == BITCOIN_FAMILY.namespace
    // A different request starts unread, so nothing carries over from the last one.
    key(review) {
        val read = rememberReviewRead(review)
        LenslyScreen(
            title = review.title,
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                ReviewActions(
                    hint = readHint(read) ?: acknowledge?.takeUnless { it.checked }?.let { "Tick the checkbox above to sign" },
                    approvable = read.read && review.warnings.none { it.level == Danger.BLOCK } && acknowledge?.checked != false,
                    onApprove = onApprove,
                    onReject = onReject,
                )
            },
        ) {
            ReviewBody(read) {
                ReviewHeader(
                    network = review.network.name,
                    testnet = review.network.testnet,
                    chainDetail = review.network.detail,
                    account = signer.name,
                    wallet = signer.walletName,
                    address = signer.address,
                    bitcoin = bitcoin,
                )
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSummary(
                    sentence = AnnotatedString(review.summary),
                    facts = review.facts.map { fact ->
                        ReviewFact(label = fact.label, value = fact.value.text(), mono = fact.value !is DisplayValue.Text)
                    },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                RiskBanner(
                    items = review.warnings.map { warning ->
                        val danger = warning.level == Danger.DANGER || warning.level == Danger.BLOCK
                        val check = acknowledge?.takeIf { it.warningCode == warning.code }
                            ?.let { RiskCheck(label = it.label, checked = it.checked, onCheckedChange = it.onCheckedChange) }
                        RiskItem(text = warning.message, tone = if (danger) BannerTone.Error else BannerTone.Warning, check = check)
                    },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                review.sections.forEach { section ->
                    ReviewSection(title = section.heading) {
                        section.rows.forEach { row ->
                            val value = row.value.text()
                            ReviewField(
                                label = row.label,
                                value = value,
                                notes = addressNotes.notesOf(value),
                                onOpen = open,
                                isAddress = row.value is DisplayValue.Address,
                                bitcoin = bitcoin,
                                lines = row.notes,
                            )
                        }
                    }
                }
            }
        }
    }
}
