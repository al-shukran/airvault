package app.lensly.app.sign

import app.airvault.chains.algorand.signer.AlgorandRequestReview
import app.airvault.chains.algorand.signer.toReview
import app.airvault.core.model.text
import app.lensly.app.addressesIn

/** The addresses an Algorand review shows, each one looked up in the Address Book. */
fun algorandCounterparties(review: AlgorandRequestReview): List<String> =
    addressesIn(review.toReview().sections.flatMap { it.rows }.map { it.value.text() })
