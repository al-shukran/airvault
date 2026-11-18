package app.lensly.core.algorand

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.AlgorandNetwork
import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.Payment
import app.airvault.chains.algorand.bytesToSign
import app.lensly.core.algorand.AlgorandRequestCheck.CLAWBACK_REFUSED
import app.lensly.core.algorand.AlgorandRequestCheck.DIFFERENT_ADDRESS
import app.lensly.core.algorand.AlgorandRequestCheck.GENESIS_HASH_MISMATCH
import app.lensly.core.algorand.AlgorandRequestCheck.NOT_A_TRANSACTION
import app.lensly.core.algorand.AlgorandRequestCheck.NOT_THIS_SENDER
import app.lensly.core.algorand.AlgorandRequestCheck.PATH_REFUSED
import app.lensly.core.algorand.AlgorandRequestCheck.REKEY_REFUSED
import app.lensly.core.algorand.AlgorandRequestCheck.UNKNOWN_ACCOUNT
import app.lensly.core.algorand.AlgorandRequestCheck.UNKNOWN_NETWORK
import app.lensly.core.ur.PathStep
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The review rules of [AlgorandRequestCheck]. The transactions come from the SDK fixtures of [AlgoTxnDecoderTest] or from its encoder
 * helpers, so each test changes one field. The wallet's accounts are account 0 with the key [KEY_A] and account 1 with [KEY_B].
 */
class AlgorandRequestCheckTest {

    @Test
    fun aPaymentFromTheSdkIsReviewedForAccountZero() {
        val review = reviewOf(AlgoTxnVectors.valid().single { it.name == "pay_basic" }.raw)

        assertEquals(0, review.accountIndex)
        assertEquals("OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ", review.signerAddress)
        val payment = assertIs<Payment>(review.txn)
        assertEquals(1_500_000UL, payment.amount)
        assertEquals(1_000UL, payment.fee)
        assertContentEquals(KEY_B, payment.receiver)
        assertEquals(AlgorandNetwork.TESTNET, review.network)
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun theReviewedBytesAndTheRequestIdPassThroughToTheReview() {
        val raw = paymentWith()
        val requestId = ByteArray(16) { 7 }

        val review = reviewOf(raw, requestId = requestId)

        assertContentEquals(raw, review.signData)
        assertContentEquals(requestId, review.requestId)
    }

    @Test
    fun aRekeyIsRefusedForAPaymentAndForAnAssetTransfer() {
        assertEquals(REKEY_REFUSED, refusalOf(paymentWith("rekey" to bin(KEY_C))))
        assertEquals(REKEY_REFUSED, refusalOf(assetWith("rekey" to bin(KEY_C))))
        assertEquals(REKEY_REFUSED, refusalOf(AlgoTxnVectors.valid().single { it.name == "pay_rekey" }.raw))
    }

    @Test
    fun anUnknownTransactionTypeIsRefused() {
        assertTrue(refusalOf(paymentWith("type" to str("keyreg"))).contains("cannot be reviewed"))
        assertIs<AlgorandRequestCheck.Result.Refused>(checkOf(AlgoTxnVectors.refused("appl_noop"), listOf(KEY_A)))
    }

    @Test
    fun aSenderThatIsNotTheSigningAccountIsRefused() {
        assertEquals(NOT_THIS_SENDER, refusalOf(paymentWith("snd" to bin(KEY_C))))
        assertEquals(NOT_THIS_SENDER, refusalOf(paymentWith("snd" to bin(KEY_B)), keys = listOf(KEY_A, KEY_B), account = 0))
    }

    @Test
    fun theSenderMustBeTheKeyOfTheAccountInThePath() {
        val review = reviewOf(paymentWith("snd" to bin(KEY_B)), keys = listOf(KEY_A, KEY_B), account = 1)

        assertEquals(1, review.accountIndex)
        assertContentEquals(KEY_B, review.signer)
    }

    @Test
    fun aCloseToAddressIsFlaggedWithTheCloseWarning() {
        val payment = reviewOf(paymentWith("close" to bin(KEY_C)))
        assertEquals(listOf(AlgorandWarning.CLOSE_TO), payment.warnings)
        assertContentEquals(KEY_C, payment.closeTo)

        val asset = reviewOf(assetWith("aclose" to bin(KEY_C)))
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.UNKNOWN_ASSET), asset.warnings)
        assertContentEquals(KEY_C, asset.closeTo)
    }

    @Test
    fun anAssetOptInIsAnAssetTransferOfZeroUnitsToTheSender() {
        val review = reviewOf(assetWith("arcv" to bin(KEY_A), "aamt" to null))

        val transfer = assertIs<AssetTransfer>(review.txn)
        assertEquals(0UL, transfer.amount)
        assertContentEquals(KEY_A, transfer.receiver)
        assertEquals(10_458_941UL, transfer.assetId)
        assertEquals(listOf(AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun anAssetTransferIsReviewedWithItsAssetIdAndRawAmount() {
        val transfer = assertIs<AssetTransfer>(reviewOf(assetWith()).txn)

        assertEquals(10_458_941UL, transfer.assetId)
        assertEquals(250UL, transfer.amount)
        assertContentEquals(KEY_B, transfer.receiver)
    }

    @Test
    fun aClawbackIsRefused() {
        assertEquals(CLAWBACK_REFUSED, refusalOf(assetWith("asnd" to bin(KEY_B))))
    }

    @Test
    fun anSdkClawbackAndAnSdkAssetRekeyAreRefused() {
        assertEquals(CLAWBACK_REFUSED, refusalOf(fixture("axfer_clawback")))
        assertEquals(REKEY_REFUSED, refusalOf(fixture("axfer_rekey")))
    }

    @Test
    fun anOptOutIsReviewedWithItsCloseWarningAndTheRemainderGoesToTheReceiverOfTheCloseOut() {
        val review = reviewOf(fixture("axfer_closeout"))

        val transfer = assertIs<AssetTransfer>(review.txn)
        assertTrue(transfer.isOptOut)
        assertEquals(0UL, transfer.amount)
        assertContentEquals(KEY_B, review.closeTo)
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun anOptOutToAnotherAddressSendsTheRemainderThereAndNotToTheReceiver() {
        val review = reviewOf(fixture("axfer_optout_to_other"))

        val transfer = assertIs<AssetTransfer>(review.txn)
        assertContentEquals(KEY_B, transfer.receiver)
        assertContentEquals(KEY_C, review.closeTo)
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun aTransferThatAlsoClosesTheHoldingIsNotAnOptOutAndWarnsAboutTheClose() {
        val review = reviewOf(fixture("axfer_close_with_amount"))

        val transfer = assertIs<AssetTransfer>(review.txn)
        assertFalse(transfer.isOptOut)
        assertEquals(250UL, transfer.amount)
        assertContentEquals(KEY_C, review.closeTo)
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun aMainnetUsdcTransferIsReviewedOnMainnet() {
        val review = reviewWith(fixture("axfer_mainnet_usdc"), CATALOG)

        assertEquals(AlgorandNetwork.MAINNET, review.network)
        val transfer = assertIs<AssetTransfer>(review.txn)
        assertEquals(31_566_704UL, transfer.assetId)
        assertEquals(1_000_000UL, transfer.amount)
        assertContentEquals(KEY_B, transfer.receiver)
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun aMainnetUsdcOptInIsAnOptInOnMainnet() {
        val review = reviewOf(fixture("axfer_optin_mainnet_usdc"))

        assertEquals(AlgorandNetwork.MAINNET, review.network)
        val transfer = assertIs<AssetTransfer>(review.txn)
        assertTrue(transfer.isOptIn)
        assertEquals(31_566_704UL, transfer.assetId)
    }

    @Test
    fun aKnownMainnetAssetIsNamedOnItsNetworkAndWarnsNothing() {
        val review = reviewWith(fixture("axfer_mainnet_usdc"), CATALOG)

        assertEquals("USDC", review.asset?.unitName)
        assertEquals(6, review.asset?.decimals)
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun aKnownTestnetAssetIsNamedOnTestnet() {
        val review = reviewWith(fixture("axfer"), CATALOG)

        assertEquals(AlgorandNetwork.TESTNET, review.network)
        assertEquals(6, review.asset?.decimals)
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun anAssetIsNamedOnlyOnTheNetworkThatListsItAndTheSameIdOnAnotherNetworkIsUnknown() {
        // Asset 10458941 is listed on mainnet here, but the review is for testnet, which lists no asset.
        val mainnetOnly = AsaCatalog.parse(
            """{"networks": {"wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=": {"genesisId": "mainnet-v1.0",
                "assets": [{"id": 10458941, "unit": "OTHER", "name": "Other", "decimals": 3}]}}}""",
        )

        val review = reviewWith(fixture("axfer"), mainnetOnly)

        assertNull(review.asset)
        assertEquals(listOf(AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    @Test
    fun anAssetTheCatalogDoesNotNameIsWarnedAndNotRefused() {
        val review = reviewOf(fixture("axfer_unknown_asset"))

        assertNull(review.asset)
        assertEquals(1_234_567UL, (review.txn as AssetTransfer).assetId)
        assertEquals(listOf(AlgorandWarning.UNKNOWN_ASSET), review.warnings)
    }

    /** The review of [raw] for account 0, with the assets [assets] names. */
    private fun reviewWith(raw: ByteArray, assets: AsaCatalog): AlgorandRequestReview =
        assertIs<AlgorandRequestCheck.Result.Ok>(AlgorandRequestCheck.check(raw, algoPath(), null, listOf(KEY_A), assets)).review

    /** The SDK fixture named [name], as the bytes the owner would have sent. */
    private fun fixture(name: String): ByteArray = AlgoTxnVectors.valid().single { it.name == name }.raw

    @Test
    fun onlyTheTestnetAndMainnetGenesisIdsAreSigned() {
        val mainnet = paymentWith("gen" to str("mainnet-v1.0"), "gh" to bin(MAINNET_GENESIS_HASH))
        assertEquals(AlgorandNetwork.MAINNET, reviewOf(mainnet).network)
        assertEquals(UNKNOWN_NETWORK, refusalOf(paymentWith("gen" to str("betanet-v1.0"))))
        assertEquals(UNKNOWN_NETWORK, refusalOf(paymentWith("gen" to null)))
    }

    @Test
    fun theGenesisHashPinsAreThePublishedHashesOfTestnetAndMainnet() {
        // Read from /v2/transactions/params on testnet-api.algonode.cloud and mainnet-api.algonode.cloud, and checked against
        // testnet-api.4160.nodely.dev and mainnet-api.4160.nodely.dev.
        assertContentEquals(TESTNET_GENESIS_HASH, AlgorandNetwork.TESTNET.genesisHash)
        assertContentEquals(MAINNET_GENESIS_HASH, AlgorandNetwork.MAINNET.genesisHash)
    }

    @Test
    fun aGenesisHashThatIsNotTheNetworksIsRefused() {
        assertEquals(GENESIS_HASH_MISMATCH, refusalOf(paymentWith("gh" to bin(MAINNET_GENESIS_HASH))))
        assertEquals(GENESIS_HASH_MISMATCH, refusalOf(paymentWith("gen" to str("mainnet-v1.0"))))
        assertEquals(GENESIS_HASH_MISMATCH, refusalOf(assetWith("gh" to bin(MAINNET_GENESIS_HASH))))
        val oneBitOff = TESTNET_GENESIS_HASH.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertEquals(GENESIS_HASH_MISMATCH, refusalOf(paymentWith("gh" to bin(oneBitOff))))
    }

    @Test
    fun aGenesisHashThatIsTheNetworksIsReviewed() {
        assertEquals(AlgorandNetwork.TESTNET, reviewOf(paymentWith()).network)
        val mainnet = paymentWith("gen" to str("mainnet-v1.0"), "gh" to bin(MAINNET_GENESIS_HASH))
        assertEquals(AlgorandNetwork.MAINNET, reviewOf(mainnet).network)
    }

    @Test
    fun aMemberOfAGroupIsFlaggedWithTheGroupWarningAndIsStillReviewed() {
        val group = ByteArray(32) { 3 }

        val review = reviewOf(paymentWith("grp" to bin(group)))

        assertContentEquals(group, review.txn.group)
        assertEquals(listOf(AlgorandWarning.GROUP), review.warnings)
        val closeAndGroup = paymentWith("grp" to bin(group), "close" to bin(KEY_C))
        assertEquals(listOf(AlgorandWarning.CLOSE_TO, AlgorandWarning.GROUP), reviewOf(closeAndGroup).warnings)
        assertEquals(emptyList(), reviewOf(paymentWith()).warnings)
    }

    @Test
    fun aPathThatIsNotTheAlgorandPathOfAWalletAccountIsRefused() {
        val raw = paymentWith()
        val wrongPaths = listOf(
            algoPath(coin = 501),
            algoPath(purpose = 49),
            algoPath(hardenedAccount = false),
            algoPath(change = 1),
            algoPath(address = 1),
            algoPath().dropLast(1),
        )

        for (path in wrongPaths) {
            val result = AlgorandRequestCheck.check(raw, path, null, listOf(KEY_A))
            assertEquals(PATH_REFUSED, assertIs<AlgorandRequestCheck.Result.Refused>(result).reason, "path $path")
        }
    }

    @Test
    fun anAccountTheWalletDoesNotHaveIsRefused() {
        assertEquals(UNKNOWN_ACCOUNT, refusalOf(paymentWith(), keys = listOf(KEY_A), account = 1))
    }

    @Test
    fun bytesThatAreNotATransactionAreRefused() {
        assertIs<AlgorandRequestCheck.Result.Refused>(checkOf(ByteArray(0), listOf(KEY_A)))
        // The signing input with its "TX" tag is not a transaction: a request that passes the tag in is refused.
        assertIs<AlgorandRequestCheck.Result.Refused>(checkOf(bytesToSign(paymentWith()), listOf(KEY_A)))
    }

    @Test
    fun aDecodedRequestIsCheckedAsItsTransactionAndItsPath() {
        val review = assertIs<AlgorandRequestCheck.Result.Ok>(AlgorandRequestCheck.check(algoRequest(paymentWith()), listOf(KEY_A))).review

        assertEquals(0, review.accountIndex)
        assertContentEquals(ByteArray(16) { 7 }, review.requestId)
    }

    @Test
    fun aMessageToSignIsRefusedBecauseLenslySignsOnlyTransactions() {
        val request = algoRequest(paymentWith(), signType = AlgoSignType.MESSAGE)

        val checked = AlgorandRequestCheck.check(request, listOf(KEY_A))
        assertEquals(NOT_A_TRANSACTION, assertIs<AlgorandRequestCheck.Result.Refused>(checked).reason)
    }

    @Test
    fun anAddressTheRequestNamesMustBeTheSigningAccountsOrNoneAtAll() {
        fun named(address: String) = AlgorandRequestCheck.check(algoRequest(paymentWith(), address = address), listOf(KEY_A))

        assertIs<AlgorandRequestCheck.Result.Ok>(named(""))
        assertIs<AlgorandRequestCheck.Result.Ok>(named(AlgoAddress.encode(KEY_A)))
        assertEquals(DIFFERENT_ADDRESS, assertIs<AlgorandRequestCheck.Result.Refused>(named(AlgoAddress.encode(KEY_B))).reason)
    }

    private fun algoRequest(
        signData: ByteArray,
        signType: AlgoSignType = AlgoSignType.TRANSACTION,
        address: String = "",
    ): AlgoSignRequest = AlgoSignRequest(
        requestId = ByteArray(16) { 7 },
        signData = signData,
        derivationPath = algoPath(),
        sourceFingerprint = null,
        address = address,
        origin = "lumio",
        signType = signType,
    )

    @Test
    fun accountIndexOfReadsOnlyTheAlgorandPath() {
        assertEquals(7, AlgorandRequestCheck.accountIndexOf(algoPath(account = 7)))
        assertEquals(null, AlgorandRequestCheck.accountIndexOf(algoPath(coin = 501)))
    }

    private fun algoPath(
        account: Int = 0,
        coin: Int = 283,
        purpose: Int = 44,
        hardenedAccount: Boolean = true,
        change: Int = 0,
        address: Int = 0,
    ): List<PathStep> = listOf(
        PathStep(purpose, true),
        PathStep(coin, true),
        PathStep(account, hardenedAccount),
        PathStep(change, false),
        PathStep(address, false),
    )

    private fun checkOf(
        raw: ByteArray,
        keys: List<ByteArray>,
        account: Int = 0,
        requestId: ByteArray? = null,
    ): AlgorandRequestCheck.Result = AlgorandRequestCheck.check(raw, algoPath(account = account), requestId, keys)

    private fun reviewOf(
        raw: ByteArray,
        keys: List<ByteArray> = listOf(KEY_A),
        account: Int = 0,
        requestId: ByteArray? = null,
    ): AlgorandRequestReview = assertIs<AlgorandRequestCheck.Result.Ok>(checkOf(raw, keys, account, requestId)).review

    private fun refusalOf(
        raw: ByteArray,
        keys: List<ByteArray> = listOf(KEY_A),
        account: Int = 0,
    ): String = assertIs<AlgorandRequestCheck.Result.Refused>(checkOf(raw, keys, account)).reason
}

/** The genesis hashes of testnet-v1.0 and mainnet-v1.0, as the node APIs publish them. */
private val TESTNET_GENESIS_HASH = java.util.Base64.getDecoder().decode("SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=")
private val MAINNET_GENESIS_HASH = java.util.Base64.getDecoder().decode("wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=")

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
