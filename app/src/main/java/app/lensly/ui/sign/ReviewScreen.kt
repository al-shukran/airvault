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
import app.airvault.core.model.Danger
import app.airvault.core.model.ChainId
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.text
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.app.notesOf
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * The one review screen for every chain (spec 3.2). The core builds a [Review] and this screen draws it, so no chain draws its own. The
 * header names the network and the signing account. The summary sentence sits over its facts. Every warning goes in one banner: an error
 * for a danger or a block, information for an info, a warning otherwise. A warning that names a tick needs it ticked before the review
 * can be signed. Each section lists its rows, all shown on first render. The request is signed only after it is read to its end, and only
 * when no warning is a block. REJECT is always enabled.
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
    /** Opens an address of the review in the Address Book, with its chain. Null leaves the address rows without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val open = addressTapOn(addressChain, onOpenAddress)
    // A different request starts unread and unticked, so nothing carries over from the last one.
    key(review) {
        val read = rememberReviewRead(review)
        var ticked by remember { mutableStateOf(emptySet<String>()) }
        val unticked = review.warnings.any { it.acknowledge != null && it.code !in ticked }
        LenslyScreen(
            title = review.title,
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                ReviewActions(
                    hint = readHint(read) ?: if (unticked) "Tick the checkbox above to sign" else null,
                    approvable = read.read && !unticked && review.warnings.none { it.level == Danger.BLOCK },
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
                    networkFact = review.network.row?.let { fact(it) },
                    account = signer.name,
                    wallet = signer.walletName,
                    address = signer.address,
                )
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSummary(
                    sentence = AnnotatedString(review.summary),
                    facts = review.facts.map { fact(it) },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                RiskBanner(
                    items = review.warnings.map { warning ->
                        RiskItem(
                            text = warning.message,
                            tone = when (warning.level) {
                                Danger.DANGER, Danger.BLOCK -> BannerTone.Error
                                Danger.INFO -> BannerTone.Info
                                Danger.CAUTION -> BannerTone.Warning
                            },
                            check = warning.acknowledge?.let { label ->
                                RiskCheck(
                                    label = label,
                                    checked = warning.code in ticked,
                                    onCheckedChange = { on -> ticked = if (on) ticked + warning.code else ticked - warning.code },
                                )
                            },
                        )
                    },
                )
                Spacer(Modifier.height(LenslySpacing.md))
                review.sections.forEach { section ->
                    ReviewSection(title = section.heading) {
                        section.rows.forEach { row ->
                            val value = row.value.text()
                            ReviewField(label = row.label, value = value, notes = addressNotes.notesOf(value), onOpen = open)
                        }
                    }
                }
            }
        }
    }
}

/** A row as a summary fact: set in the monospace face when its value is an amount, an address or data, or when the value asks for it. */
private fun fact(row: ReviewRow): ReviewFact {
    val value = row.value
    return ReviewFact(label = row.label, value = value.text(), mono = value !is DisplayValue.Text || value.mono)
}
