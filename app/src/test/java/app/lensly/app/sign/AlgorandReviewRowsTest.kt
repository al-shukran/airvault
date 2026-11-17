package app.lensly.app.sign

import app.lensly.core.algorand.AlgorandRequestCheck
import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.algorand.AlgorandWarning
import app.lensly.core.ur.PathStep
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/**
 * The rows, sentence and warning of an Algorand review. The transactions are the SDK fixtures of the decoder tests (vectors.json), and each
 * one is checked as a request for account 0 of a wallet whose account 0 is the sender's key.
 */
class AlgorandReviewRowsTest {

    @Test
    fun aPaymentShowsItsSenderReceiverAmountAndFeeInAlgoToSixDecimals() {
        val review = reviewOf(PAY_BASIC)

        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Receiver" to RECEIVER_ADDRESS,
                "Amount" to "1.500000 ALGO",
                "Fee" to "0.001000 ALGO",
            ),
            algorandRows(review),
        )
        assertEquals("Sends 1.500000 ALGO to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(listOf("Fee" to "0.001000 ALGO"), algorandFacts(review))
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun aPaymentWithANoteAndACloseToShowsBothAndTheCloseWarning() {
        val review = reviewOf(PAY_NOTE_CLOSE)

        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Receiver" to RECEIVER_ADDRESS,
                "Amount" to "2000.000000 ALGO",
                "Fee" to "0.001000 ALGO",
                "Note" to "rent for March",
                "Close remainder to" to CLOSE_ADDRESS,
            ),
            algorandRows(review),
        )
        assertEquals(listOf(AlgorandWarning.CLOSE_TO), review.warnings)
        assertEquals(
            "This closes the account. All of its remaining ALGO goes to the close-to address below, and the account is removed.",
            algorandWarningText(review, AlgorandWarning.CLOSE_TO),
        )
    }

    @Test
    fun aGroupWarningSaysOtherTransactionsOfTheGroupAreNotShown() {
        val review = reviewOf(PAY_BASIC)

        assertEquals(
            "Part of a group: other transactions in the group are not shown",
            algorandWarningText(review, AlgorandWarning.GROUP),
        )
    }

    @Test
    fun aNoteThatIsNotPrintableShowsItsBytesAsHex() {
        val review = reviewOf(PAY_NOTE_CLOSE.replace(NOTE_BIN, "c4020001"))

        assertEquals("0x0001", algorandRows(review).single { it.first == "Note" }.second)
    }

    @Test
    fun aTransactionWithoutANoteShowsNoNoteRow() {
        assertEquals(emptyList(), algorandRows(reviewOf(PAY_BASIC)).filter { it.first == "Note" })
    }

    @Test
    fun anAssetTransferShowsItsAssetIdAndItsAmountInBaseUnits() {
        val review = reviewOf(AXFER)

        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Asset ID" to "10458941",
                "Amount" to "250 base units",
                "Receiver" to RECEIVER_ADDRESS,
                "Fee" to "0.001000 ALGO",
            ),
            algorandRows(review),
        )
        assertEquals("Sends 250 base units of asset 10458941 to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun anAssetOptInIsDescribedAsOptingIn() {
        val review = reviewOf(AXFER_OPTIN)

        assertEquals("Opt in to asset 10458941.", algorandSentence(review))
        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Asset ID" to "10458941",
                "Amount" to "0 base units",
                "Receiver" to SENDER_ADDRESS,
                "Fee" to "0.001000 ALGO",
            ),
            algorandRows(review),
        )
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun anAssetOptOutSaysWhatItRemovesAndWhereTheRemainingBalanceGoes() {
        val review = reviewOf(AXFER_CLOSEOUT)

        assertEquals("Opt out of asset 10458941, remaining balance goes to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Asset ID" to "10458941",
                "Amount" to "0 base units",
                "Receiver" to RECEIVER_ADDRESS,
                "Fee" to "0.001000 ALGO",
                "Close holding to" to RECEIVER_ADDRESS,
            ),
            algorandRows(review),
        )
        assertEquals(listOf(AlgorandWarning.CLOSE_TO), review.warnings)
    }

    @Test
    fun anOptOutToAnotherAddressNamesThatAddressNotTheReceiver() {
        val review = reviewOf(AXFER_OPTOUT_TO_OTHER)

        assertEquals("Opt out of asset 10458941, remaining balance goes to $CLOSE_ADDRESS.", algorandSentence(review))
        assertEquals(CLOSE_ADDRESS, algorandRows(review).single { it.first == "Close holding to" }.second)
    }

    @Test
    fun aTransferThatAlsoClosesTheHoldingSaysWhereTheRestGoes() {
        val review = reviewOf(AXFER_CLOSE_WITH_AMOUNT)

        assertEquals(
            "Sends 250 base units of asset 10458941 to $RECEIVER_ADDRESS. The rest goes to $CLOSE_ADDRESS, and the asset is removed.",
            algorandSentence(review),
        )
        assertEquals(listOf(AlgorandWarning.CLOSE_TO), review.warnings)
    }

    @Test
    fun aCloseToOnAnAssetTransferIsNamedAsCloseHoldingWithItsWarning() {
        val review = reviewOf(AXFER.replace(AXFER_HEADER_OLD, AXFER_HEADER_WITH_ACLOSE))

        assertEquals(CLOSE_ADDRESS, algorandRows(review).single { it.first == "Close holding to" }.second)
        assertEquals(listOf(AlgorandWarning.CLOSE_TO), review.warnings)
        assertEquals(
            "This sends all of the remaining units of this asset to the close-to address below and removes the asset from the account.",
            algorandWarningText(review, AlgorandWarning.CLOSE_TO),
        )
    }

    /** The review of [hex] for account 0, whose key is the sender's, as the wallet's account list holds it. */
    private fun reviewOf(hex: String): AlgorandRequestReview {
        val path = listOf(
            PathStep(44, true),
            PathStep(283, true),
            PathStep(0, true),
            PathStep(0, false),
            PathStep(0, false),
        )
        val result = AlgorandRequestCheck.check(hexBytes(hex), path, null, listOf(hexBytes(SENDER_PUB)))
        return assertIs<AlgorandRequestCheck.Result.Ok>(result).review
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private companion object {
        const val SENDER_PUB = "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538"
        const val SENDER_ADDRESS = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
        const val RECEIVER_ADDRESS = "YEO767URJECB5BKVVIH4ZOBCN5EHGHLU5RCQPRBSCPQ3OBPVMFOYGSM47U"
        const val CLOSE_ADDRESS = "RU7NDDFYFPLS5K42DPBBU57OWCL3P7LI7FZ7OBQDNUQZUFS5ABWULNOFFM"

        /** The `pay_basic` vector: 1.5 ALGO from account 0 to account 1. */
        const val PAY_BASIC =
            "89a3616d74ce0016e360a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b5" +
                "18a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3726376c420c11dff7e914904" +
                "1e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd" +
                "1467c72e3c660fc8c2bd2c6538a474797065a3706179"

        /** The `pay_note_close` vector: 2000 ALGO with the note "rent for March" and a close-to address. */
        const val PAY_NOTE_CLOSE =
            "8ba3616d74ce77359400a5636c6f7365c4208d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006d" +
                "a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f" +
                "1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a46e6f7465c40e72656e7420666f72204d61726368a372" +
                "6376c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b58" +
                "1e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a474797065a3706179"

        /** The note bytes of `pay_note_close`, with their header: "rent for March". */
        const val NOTE_BIN = "c40e72656e7420666f72204d61726368"

        /** The `axfer` vector: 250 units of asset 10458941 to account 1. */
        const val AXFER =
            "8aa461616d74ccfaa461726376c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da36665" +
                "65cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb" +
                "0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd14" +
                "67c72e3c660fc8c2bd2c6538a474797065a56178666572a478616964ce009f973d"

        /** The `axfer_optin` vector: the sender receives zero units of asset 10458941, so it opts in. */
        const val AXFER_OPTIN =
            "89a461726376c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a3666565cd03e8a26676" +
                "ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb0f71f059a7ac20" +
                "dec62f7f70e5093a22a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8" +
                "c2bd2c6538a474797065a56178666572a478616964ce009f973d"

        /** The first bytes of `axfer`: a ten-entry map, then `aamt` and the start of `arcv`. */
        const val AXFER_HEADER_OLD = "8aa461616d74ccfaa461726376"

        /** The same header with an `aclose` entry for account 2's key, the close-to address, added after `aamt` in canonical key order. */
        const val AXFER_HEADER_WITH_ACLOSE =
            "8ba461616d74ccfaa661636c6f7365c4208d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006da4" +
                "61726376"

        /** The `axfer_closeout` vector: an opt-out by close-out, 0 units with the remaining holding to account 1 (the receiver). */
        const val AXFER_CLOSEOUT =
            "8aa661636c6f7365c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da461726376c420c1" +
                "1dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3666565cd03e8a26676ce02faf080a367656e" +
                "ac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22" +
                "a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a4747970" +
                "65a56178666572a478616964ce009f973d"

        /** The `axfer_optout_to_other` vector: an opt-out whose remaining holding goes to account 2, not to the receiver. */
        const val AXFER_OPTOUT_TO_OTHER =
            "8aa661636c6f7365c4208d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006da461726376c420c1" +
                "1dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3666565cd03e8a26676ce02faf080a367656e" +
                "ac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22" +
                "a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a4747970" +
                "65a56178666572a478616964ce009f973d"

        /** The `axfer_close_with_amount` vector: 250 units to account 1, and the rest of the holding to account 2. */
        const val AXFER_CLOSE_WITH_AMOUNT =
            "8ba461616d74ccfaa661636c6f7365c4208d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006da4" +
                "61726376c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3666565cd03e8a26676ce02" +
                "faf080a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec6" +
                "2f7f70e5093a22a26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd" +
                "2c6538a474797065a56178666572a478616964ce009f973d"
    }
}
