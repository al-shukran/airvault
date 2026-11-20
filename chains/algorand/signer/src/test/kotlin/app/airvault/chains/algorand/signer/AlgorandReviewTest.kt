package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgoTxn
import app.airvault.chains.algorand.AlgorandNetwork
import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.Payment
import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.algorand.algorandTestnet
import app.airvault.core.model.Danger
import app.airvault.core.model.Review
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import app.airvault.chains.algorand.AlgoAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlgorandReviewTest {
    private val sender = ByteArray(32) { 7 }
    private val receiver = ByteArray(32) { 9 }
    private val network = AlgorandNetwork.TESTNET

    private fun payment(lease: ByteArray? = null, group: ByteArray? = null, closeTo: ByteArray? = null, note: ByteArray = ByteArray(0)) = Payment(
        sender = sender,
        fee = 1_000UL,
        firstValid = 100UL,
        lastValid = 200UL,
        genesisId = network.genesisId,
        genesisHash = network.genesisHash,
        note = note,
        lease = lease,
        group = group,
        rekeyTo = null,
        receiver = receiver,
        amount = 1_500_000UL,
        closeTo = closeTo,
    )

    private fun optIn() = AssetTransfer(
        sender = sender,
        fee = 1_000UL,
        firstValid = 100UL,
        lastValid = 200UL,
        genesisId = network.genesisId,
        genesisHash = network.genesisHash,
        note = ByteArray(0),
        lease = null,
        group = null,
        rekeyTo = null,
        assetId = 31_566_704UL,
        amount = 0UL,
        receiver = sender,
        closeTo = null,
        assetSender = null,
    )

    private fun reviewOf(txn: AlgoTxn, origin: String? = null): Review = AlgorandRequestReview(
        accountIndex = 0,
        signer = sender,
        txn = txn,
        network = network,
        signData = ByteArray(0),
        requestId = null,
        origin = origin,
    ).toReview()

    private fun Review.labels(): List<String> = sections.flatMap { it.rows }.map { it.label }

    @Test
    fun aPaymentShowsEveryFieldItDecodes() {
        val review = reviewOf(payment())
        assertTrue(review.unaccounted().isEmpty(), "every decoded field is shown or excluded")
        assertTrue(review.warnings.none { it.level == Danger.BLOCK })
        assertEquals(listOf("Sender", "Receiver", "Amount", "Fee", "Valid from Round", "Valid until Round"), review.labels())
        assertEquals("Sends 1.500000 ALGO to ${AlgoAddress.encode(receiver)}.", review.summary.text())
    }

    @Test
    fun leaseAndGroupAreShownAsHex() {
        val review = reviewOf(payment(lease = ByteArray(32) { 1 }, group = ByteArray(32) { 2 }))
        val rows = review.sections.flatMap { it.rows }.associate { it.label to it.value.text() }
        assertEquals("0x" + "01".repeat(32), rows["Lease"])
        assertEquals("0x" + "02".repeat(32), rows["Group ID"])
        assertTrue(review.unaccounted().isEmpty())
    }

    /** U+E0002 is unassigned. It draws nothing, so the note reads "rent" while the signed bytes hold more: the note shows its bytes. */
    @Test
    fun aNoteWithAnUnassignedCharacterShowsItsBytes() {
        val note = "rent".toByteArray(Charsets.UTF_8) + String(Character.toChars(0xE0002)).toByteArray(Charsets.UTF_8)

        val row = reviewOf(payment(note = note)).sections.flatMap { it.rows }.single { it.label == "Note" }

        assertEquals("0x72656e74f3a08082", row.value.text())
    }

    @Test
    fun anOptInIsTitledAsOneAndShowsNoReceiver() {
        val review = reviewOf(optIn())
        assertEquals("Opt In to Asset", review.title)
        assertTrue(review.warnings.none { it.level == Danger.BLOCK })
        assertTrue("Receiver" !in review.labels())
    }

    /** A close-to payment empties the account, so its DANGER names a check the owner ticks before the review can be signed. */
    @Test
    fun aCloseToPaymentIsADangerThatNeedsItsTick() {
        val warning = reviewOf(payment(closeTo = ByteArray(32) { 5 })).warnings.single { it.code == "algorand.close-to" }
        assertEquals(Danger.DANGER, warning.level)
        assertNotNull(warning.acknowledge)
    }

    /** The origin the wallet names is its claim: it shows under its label with the note that Lensly cannot verify it, and the review covers it. */
    @Test
    fun anOriginIsShownAsAClaimThatLenslyCannotVerify() {
        val review = reviewOf(payment(), origin = "Lumio")
        val row = review.sections.flatMap { it.rows }.single { it.label == "Claimed by Request (Not Verified)" }
        assertEquals("Lumio", row.value.text())
        assertEquals(listOf("Named by the wallet. Lensly cannot verify it."), row.notes)
        assertTrue(review.unaccounted().isEmpty(), "the origin row accounts for the origin field")
    }

    @Test
    fun aRequestWithNoOriginHasNoOriginRow() {
        assertTrue("Claimed by Request (Not Verified)" !in reviewOf(payment()).labels())
    }

    /** The chain ID is the first 32 characters of the URL-safe base64 genesis hash: `+` and `/` in standard base64 become `-` and `_`. */
    @Test
    fun theChainIdIsTheUrlSafeBase64OfTheGenesisHash() {
        assertEquals(algorandMainnet, algorandChainId(AlgorandNetwork.MAINNET.genesisHash))
        assertEquals(algorandTestnet, algorandChainId(AlgorandNetwork.TESTNET.genesisHash))
        val chain = algorandChainId(ByteArray(32) { 0xFB.toByte() })
        assertTrue(chain.caip2.startsWith("algorand:-_v7"), chain.caip2)
        assertTrue(Regex("algorand:[-_a-zA-Z0-9]{1,32}").matches(chain.caip2), chain.caip2)
    }
}
