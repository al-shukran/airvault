package app.lensly.app.sign

import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.toReview
import app.airvault.core.model.text
import app.lensly.app.addressesIn

/** The addresses a Solana review shows, each one looked up in the Address Book. */
fun solanaCounterparties(review: SolanaRequestReview): List<String> =
    addressesIn(review.toReview().sections.flatMap { it.rows }.map { it.value.text() })
