package app.lensly.app.sign

import app.airvault.chains.algorand.signer.AlgorandRequestCheck
import app.airvault.chains.algorand.signer.AlgorandRequestReview
import app.airvault.chains.algorand.signer.AlgorandWarning
import app.airvault.chains.algorand.signer.AsaCatalog
import app.airvault.chains.algorand.signer.toReview
import app.airvault.chains.algorand.signer.warningText
import app.airvault.core.model.text
import app.lensly.core.ur.PathStep
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/** A catalog that names USDC on mainnet and on testnet, as the bundled catalog does. */
private val CATALOG: AsaCatalog = AsaCatalog.parse(
    """
    {"networks": {
      "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=": {"genesisId": "mainnet-v1.0",
        "assets": [{"id": 31566704, "unit": "USDC", "name": "USDC", "decimals": 6}]},
      "SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=": {"genesisId": "testnet-v1.0",
        "assets": [{"id": 10458941, "unit": "USDC", "name": "USDC", "decimals": 6}]}
    }}
    """.trimIndent(),
)

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
                "Valid from Round" to "50000000",
                "Valid until Round" to "50001000",
            ),
            algorandRows(review),
        )
        assertEquals("Sends 1.500000 ALGO to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(emptyList(), algorandFacts(review))
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
                "Valid from Round" to "50000000",
                "Valid until Round" to "50001000",
                "Note" to "rent for March",
                "Close Remainder To" to CLOSE_ADDRESS,
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
    fun aLeaseAndAGroupIdAreShownInHexWhenThePayloadCarriesThem() {
        val review = reviewOf(withLeaseAndGroup(PAY_BASIC))

        assertEquals("0x" + "33".repeat(32), algorandRows(review).single { it.first == "Lease" }.second)
        assertEquals("0x" + "22".repeat(32), algorandRows(review).single { it.first == "Group ID" }.second)
    }

    @Test
    fun aTransactionWithoutANoteShowsNoNoteRow() {
        assertEquals(emptyList(), algorandRows(reviewOf(PAY_BASIC)).filter { it.first == "Note" })
    }

    @Test
    fun anUnknownAssetShowsItsIdAndItsAmountInBaseUnitsWithAWarning() {
        val review = reviewOf(AXFER)

        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Asset" to "Unknown asset #10458941",
                "Asset ID" to "10458941",
                "Amount" to "250 base units",
                "Receiver" to RECEIVER_ADDRESS,
                "Fee" to "0.001000 ALGO",
                "Valid from Round" to "50000000",
                "Valid until Round" to "50001000",
            ),
            algorandRows(review),
        )
        assertEquals("Sends 250 base units of asset 10458941 to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(listOf(AlgorandWarning.UNKNOWN_ASSET), review.warnings)
        assertEquals(
            "Unknown asset #10458941: Lensly does not know its name or decimals, so its amount is shown in base units.",
            algorandWarningText(review, AlgorandWarning.UNKNOWN_ASSET),
        )
    }

    @Test
    fun anAssetOptInShowsTheAssetAndTheFeeButNoZeroAmountOrReceiver() {
        val review = reviewOf(AXFER_OPTIN)

        assertEquals("Opt in to asset 10458941.", algorandSentence(review))
        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Asset" to "Unknown asset #10458941",
                "Asset ID" to "10458941",
                "Fee" to "0.001000 ALGO",
                "Valid from Round" to "50000000",
                "Valid until Round" to "50001000",
            ),
            algorandRows(review),
        )
        assertEquals(listOf(AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun onlyAnAssetOptInIsTitledAsOptingIn() {
        assertEquals("Opt In to Asset", algorandTitle(reviewOf(AXFER_OPTIN)))
        assertEquals("Opt In to Asset", algorandTitle(reviewWith(AXFER_OPTIN, CATALOG)))
        assertEquals("Review", algorandTitle(reviewOf(AXFER)))
        assertEquals("Review", algorandTitle(reviewOf(AXFER_CLOSEOUT)))
        assertEquals("Review", algorandTitle(reviewOf(PAY_BASIC)))
    }

    @Test
    fun anAssetOptOutSaysWhatItRemovesAndWhereTheRemainingBalanceGoes() {
        val review = reviewOf(AXFER_CLOSEOUT)

        assertEquals("Opt out of asset 10458941, remaining balance goes to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Asset" to "Unknown asset #10458941",
                "Asset ID" to "10458941",
                "Amount" to "0 base units",
                "Receiver" to RECEIVER_ADDRESS,
                "Fee" to "0.001000 ALGO",
                "Valid from Round" to "50000000",
                "Valid until Round" to "50001000",
                "Close Holding To" to RECEIVER_ADDRESS,
            ),
            algorandRows(review),
        )
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun anOptOutToAnotherAddressNamesThatAddressNotTheReceiver() {
        val review = reviewOf(AXFER_OPTOUT_TO_OTHER)

        assertEquals("Opt out of asset 10458941, remaining balance goes to $CLOSE_ADDRESS.", algorandSentence(review))
        assertEquals(CLOSE_ADDRESS, algorandRows(review).single { it.first == "Close Holding To" }.second)
    }

    @Test
    fun aTransferThatAlsoClosesTheHoldingSaysWhereTheRestGoes() {
        val review = reviewOf(AXFER_CLOSE_WITH_AMOUNT)

        assertEquals(
            "Sends 250 base units of asset 10458941 to $RECEIVER_ADDRESS. The rest goes to $CLOSE_ADDRESS, and the asset is removed.",
            algorandSentence(review),
        )
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun aCloseToOnAnAssetTransferIsNamedAsCloseHoldingWithItsWarning() {
        val review = reviewOf(AXFER.replace(AXFER_HEADER_OLD, AXFER_HEADER_WITH_ACLOSE))

        assertEquals(CLOSE_ADDRESS, algorandRows(review).single { it.first == "Close Holding To" }.second)
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.UNKNOWN_ASSET), review.warnings)
        assertEquals(
            "This sends all of the remaining units of this asset to the close-to address below and removes the asset from the account.",
            algorandWarningText(review, AlgorandWarning.CLOSE_TO),
        )
    }

    @Test
    fun aKnownTestnetUsdcTransferIsShownInUsdcUnits() {
        val review = reviewWith(AXFER, CATALOG)

        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Asset" to "USDC",
                "Asset ID" to "10458941",
                "Amount" to "0.000250 USDC",
                "Receiver" to RECEIVER_ADDRESS,
                "Fee" to "0.001000 ALGO",
                "Valid from Round" to "50000000",
                "Valid until Round" to "50001000",
            ),
            algorandRows(review),
        )
        assertEquals("Sends 0.000250 USDC to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun aKnownMainnetUsdcTransferIsShownOnMainnetInUsdcUnits() {
        val review = reviewWith(AXFER_MAINNET_USDC, CATALOG)

        assertEquals("Algorand Mainnet", review.network.displayName)
        assertEquals("1.000000 USDC", algorandRows(review).single { it.first == "Amount" }.second)
        assertEquals("Sends 1.000000 USDC to $RECEIVER_ADDRESS.", algorandSentence(review))
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun aKnownUsdcOptInAndOptOutAreNamedByTheAsset() {
        assertEquals("Opt in to USDC.", algorandSentence(reviewWith(AXFER_OPTIN, CATALOG)))

        val optOut = reviewWith(AXFER_CLOSEOUT, CATALOG)
        assertEquals("Opt out of USDC, remaining balance goes to $RECEIVER_ADDRESS.", algorandSentence(optOut))
        assertEquals(listOf(AlgorandWarning.CLOSE_TO), optOut.warnings)
    }

    @Test
    fun anAssetIsNamedOnlyOnTheNetworkThatListsItInTheReview() {
        // The testnet review finds no asset here: this catalog lists asset 10458941 on mainnet only.
        val mainnetOnly = AsaCatalog.parse(
            """{"networks": {"wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=": {"genesisId": "mainnet-v1.0",
                "assets": [{"id": 10458941, "unit": "OTHER", "name": "Other", "decimals": 3}]}}}""",
        )

        assertEquals("Unknown asset #10458941", algorandRows(reviewWith(AXFER, mainnetOnly)).single { it.first == "Asset" }.second)
    }

    /** The review of [hex] for account 0, whose key is the sender's, with the assets [assets] names. */
    private fun reviewWith(hex: String, assets: AsaCatalog): AlgorandRequestReview {
        val path = listOf(
            PathStep(44, true),
            PathStep(283, true),
            PathStep(0, true),
            PathStep(0, false),
            PathStep(0, false),
        )
        val result = AlgorandRequestCheck.check(hexBytes(hex), path, null, listOf(hexBytes(SENDER_PUB)), assets)
        return assertIs<AlgorandRequestCheck.Result.Ok>(result).review
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

    /** [hex] with a group ID of 0x22 repeated and a lease of 0x33 repeated, each in its canonical key position. */
    private fun withLeaseAndGroup(hex: String): String = hex
        .replaceFirst("89a3616d74", "8ba3616d74")
        .replaceFirst("a26c76ce02faf468", "a3677270c420${"22".repeat(32)}a26c76ce02faf468a26c78c420${"33".repeat(32)}")

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

        /** The `axfer_mainnet_usdc` vector: 1 USDC (1000000 base units) on mainnet to account 1. */
        const val AXFER_MAINNET_USDC =
            "8aa461616d74ce000f4240a461726376c420c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615d" +
                "a3666565cd03e8a26676ce02faf080a367656eac6d61696e6e65742d76312e30a26768c420c061c4d8fc1dbdded2d7604be4" +
                "568e3f6d041987ac37bde4b620b5ab39248adfa26c76ce02faf468a3736e64c4207491ae7345271b581e36e46aba59dd0179" +
                "b9bd1467c72e3c660fc8c2bd2c6538a474797065a56178666572a478616964ce01e1ab70"
    }
}

private fun algorandRows(review: AlgorandRequestReview): List<Pair<String, String>> =
    review.toReview().sections.flatMap { it.rows }.map { it.label to it.value.text() }

private fun algorandSentence(review: AlgorandRequestReview): String = review.toReview().summary.text()

private fun algorandFacts(review: AlgorandRequestReview): List<Pair<String, String>> =
    review.toReview().facts.map { it.label to it.value.text() }

private fun algorandTitle(review: AlgorandRequestReview): String = review.toReview().title

private fun algorandWarningText(review: AlgorandRequestReview, warning: AlgorandWarning): String = review.warningText(warning)
