package app.lensly.app.sign

import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.toReview
import app.airvault.core.model.text
import app.lensly.ui.sign.ReviewFact

/** The label and value rows of a Solana review, in order: what the review screen and the Review Record show. */
internal fun solanaRows(review: SolanaRequestReview): List<Pair<String, String>> =
    review.toReview().sections.flatMap { it.rows }.map { it.label to it.value.text() }

/** The sentence at the top of a Solana review. */
internal fun solanaSentence(review: SolanaRequestReview): String = review.toReview().summary

/** The facts under the sentence of a Solana review, by label. */
internal fun solanaFacts(review: SolanaRequestReview): List<Pair<String, String>> =
    review.toReview().facts.map { it.label to it.value.text() }

/** The network row of a Solana review's header, as the screen draws it: its label, its value, and set in the sans face. */
internal fun solanaNetworkFact(review: SolanaRequestReview): ReviewFact {
    val row = checkNotNull(review.toReview().network.row)
    return ReviewFact(label = row.label, value = row.value.text(), mono = false)
}

/** Whether a Solana review asks the owner to tick before it can be signed. */
internal fun solanaNeedsUnderstanding(review: SolanaRequestReview): Boolean = review.toReview().warnings.any { it.acknowledge != null }
