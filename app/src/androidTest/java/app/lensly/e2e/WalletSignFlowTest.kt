package app.lensly.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lensly.app.WalletStore
import app.lensly.app.export.PairingExport
import app.lensly.app.export.partsOf
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.AccountXpub
import app.lensly.ui.flow.SignStep
import app.lensly.ui.sign.HOLD_TO_SIGN_TAG
import app.lensly.ui.sign.UNKNOWN_UNIT
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

/** The account name the review shows for account 0 of [E2E_WALLET_NAME]: its Auto-Name, as the wallet names it. */
private const val ACCOUNT_LABEL = "E2E Wallet 0"

/** A chain that is in no registry, so the review carries the warning and asks for the explicit check. */
private const val UNKNOWN_CHAIN_ID = 999_999L

private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private val ONE_CENT_WEI: BigInteger = BigInteger.TEN.pow(16)

/**
 * The EVM wallet signing flow, end to end, on a device: a wallet is created in the vault, its pairing export is checked, an
 * `eth-sign-request` built for that wallet is fed to the scanner through the real [app.lensly.ui.flow.SignWalletRoute], the review is read
 * and approved with the App Password, and the `eth-signature` that comes back is checked against the request.
 *
 * The wallet is made through the public vault and wallet APIs rather than the create ceremony. The ceremony screens have their own tests.
 * The request is generated from the wallet the test made, so the from-address check in [app.lensly.app.sign.SignPipeline] is real.
 */
@RunWith(AndroidJUnit4::class)
class WalletSignFlowTest : SignFlowE2eBase() {

    @Test
    fun walletSignsAnUnknownChainRequestAndShowsTheSignatureUr() {
        val wallet = openWallet { _, _ -> }
        val store = WalletStore(wallet.session)
        val xpub = requireNotNull(store.meta(wallet.id)) { "the wallet record must read back" }.accountKeys.first()
        checkPairingExport(xpub, wallet.fingerprint)
        val address = store.addresses(wallet.id, 1).single()
        val request = signRequestFor(wallet.fingerprint, Numeric.hexStringToByteArray(address.removePrefix("0x")))

        val driver = driverFor(wallet, request.parts)
        driver.open()
        val review = (driver.awaitReview { it is SignStep.Review } as SignStep.Review).ready.review

        assertEquals(address, review.fromAddress)
        assertEquals(UNKNOWN_CHAIN_ID, review.chainId)
        assertEquals(ACCOUNT_LABEL, review.fromAccountLabel)

        // The screen: an unknown chain warns in the banner and its chip names it, and APPROVE waits for the details to be read and the
        // explicit check to be ticked.
        assertTrue(review.toAddress.equals(RECIPIENT, ignoreCase = true))
        compose.onNode(hasText("Not in Lensly's registry", substring = true)).assertExists()
        compose.onNode(hasText("Unknown network (chain id $UNKNOWN_CHAIN_ID)", substring = true)).assertExists()
        // An unknown chain has no decimals, so its amount is the raw wei, in base units: one cent is 10^16.
        compose.onNode(hasText("Send $ONE_CENT_WEI $UNKNOWN_UNIT", substring = true)).assertExists()
        // The header and the account row both name the account now, so the label is looked for among them.
        compose.onAllNodesWithText(ACCOUNT_LABEL).onFirst().assertExists()

        driver.readToTheEnd()
        assertTrue("the review shows its check hint", checkHintShown())
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
        // The check is a row of the risk banner, above the end of the review, so it is scrolled into view before it is ticked.
        compose.onNodeWithText("I checked the chain ID and contract").performScrollTo().performClick()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()

        val parts = driver.approveAndSign { it is SignStep.Confirming }
        assertSignatureEchoes(request, parts, address)
    }

    private fun checkHintShown(): Boolean =
        compose.onAllNodesWithText("Tick the checkbox above to sign").fetchSemanticsNodes().isNotEmpty()

    /**
     * The pairing export of the wallet is the `ur:crypto-multi-accounts` an online wallet imports, and its first account key must carry the
     * account public key and chain code the wallet was made with.
     */
    private fun checkPairingExport(xpub: AccountXpub, fingerprint: Int) {
        val exportParts = PairingExport.urParts(listOf(xpub), fingerprint, "0.1.0", E2E_WALLET_NAME)
        val exportScanner = UrScanner()
        exportParts.forEach { part -> assertTrue("the pairing part must scan back: $part", exportScanner.receive(part)) }
        val exportUr = requireNotNull(exportScanner.result()) { "the pairing export must form one UR" }
        assertEquals("crypto-multi-accounts", exportUr.type)
        val exportKey = ((fieldsOf(exportUr)[2L] as Cbor.Arr).items.first() as Cbor.Tagged).value as Cbor.Dict
        val exportFields = exportKey.entries.associate { (key, value) -> (key as Cbor.Uint).value to value }
        assertArrayEquals(xpub.publicKey, (exportFields[3L] as Cbor.Bytes).value)
        assertArrayEquals(xpub.chainCode, (exportFields[4L] as Cbor.Bytes).value)
    }

    /**
     * An `eth-sign-request` for the wallet with [fingerprint] and [addressBytes], as the UR frames the scanner will see. It is built here, for
     * the address the test wallet really has, because the pipeline refuses a request for any other address.
     */
    private fun signRequestFor(fingerprint: Int, addressBytes: ByteArray): Request {
        val requestId = ByteArray(16) { index -> (index + 1).toByte() }
        val tx = TransactionEncoder.encode(
            RawTransaction.createTransaction(
                UNKNOWN_CHAIN_ID,
                BigInteger.valueOf(7),
                BigInteger.valueOf(21_000),
                RECIPIENT,
                ONE_CENT_WEI,
                "0x",
                BigInteger.valueOf(1_500_000_000L),
                BigInteger.valueOf(30_000_000_000L),
            ),
        )
        val payload = Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(37L, Cbor.Bytes(requestId)),
                    Cbor.Uint(2L) to Cbor.Bytes(tx),
                    Cbor.Uint(3L) to Cbor.Uint(4L), // TYPED_TRANSACTION, an EIP-2718 0x02 payload.
                    Cbor.Uint(4L) to Cbor.Uint(UNKNOWN_CHAIN_ID),
                    Cbor.Uint(5L) to Cbor.Tagged(
                        304L,
                        Cbor.Dict(
                            listOf(
                                Cbor.Uint(1L) to Cbor.Arr(
                                    listOf(
                                        Cbor.Uint(44L), Cbor.Bool(true),
                                        Cbor.Uint(60L), Cbor.Bool(true),
                                        Cbor.Uint(0L), Cbor.Bool(true),
                                        Cbor.Uint(0L), Cbor.Bool(false),
                                        Cbor.Uint(0L), Cbor.Bool(false),
                                    ),
                                ),
                                Cbor.Uint(2L) to Cbor.Uint(fingerprint.toLong() and 0xFFFFFFFFL),
                            ),
                        ),
                    ),
                    Cbor.Uint(6L) to Cbor.Bytes(addressBytes),
                    Cbor.Uint(7L) to Cbor.Text("lensly-e2e"),
                ),
            ),
        )
        val parts = partsOf(framesOf(UR("eth-sign-request", payload), DEFAULT_MAX_FRAGMENT))
        return Request(requestId = requestId, signData = tx, parts = parts)
    }

    /**
     * Asserts that [parts] are the `ur:eth-signature` answer to [request]: the frames form one UR, it echoes the request ID,
     * it carries a 65-byte signature, and that signature recovers to [expectedAddress].
     *
     * The recovery is what ties the answer to the wallet. Shape alone would pass for a signature made with any key. The
     * request is an EIP-1559 typed transaction (dataType 4), so the app encodes v as the recovery id, one byte of 0 or 1,
     * and the signing hash is keccak256 of the exact `0x02` payload the request carried.
     */
    private fun assertSignatureEchoes(request: Request, parts: List<String>, expectedAddress: String) {
        assertTrue("no signature frames", parts.isNotEmpty())
        assertTrue(
            "the first frame is not an eth-signature: ${parts.first().take(40)}",
            parts.first().uppercase().startsWith("UR:ETH-SIGNATURE"),
        )
        val ur = urOfParts(parts, "eth-signature")

        val fields = fieldsOf(ur)
        val echo = (fields[1L] as Cbor.Tagged).value as Cbor.Bytes
        assertArrayEquals(request.requestId, echo.value)
        val signature = (fields[2L] as Cbor.Bytes).value
        assertEquals(65, signature.size)

        // A typed transaction carries the recovery id in v itself, not 27 or 28 and not the EIP-155 chain form.
        val recId = signature[64].toInt() and 0xFF
        assertTrue("v must be the recovery id (0 or 1) for a typed transaction, was $recId", recId in 0..1)
        val publicKey = Sign.signedMessageHashToKey(
            Hash.sha3(request.signData),
            Sign.SignatureData(
                (27 + recId).toByte(),
                signature.copyOfRange(0, 32),
                signature.copyOfRange(32, 64),
            ),
        )
        val recovered = Keys.getAddress(publicKey).lowercase().removePrefix("0x")
        assertEquals(
            "the signature must recover to the wallet address",
            expectedAddress.lowercase().removePrefix("0x"),
            recovered,
        )
    }

    private class Request(val requestId: ByteArray, val signData: ByteArray, val parts: List<String>)
}
