package app.lensly.e2e

import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.export.PairingExport
import app.lensly.app.export.partsOf
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.RealClock
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultSession
import app.lensly.ui.flow.SignFlowViewModel
import app.lensly.ui.flow.SignStep
import app.lensly.ui.flow.SignWalletRoute
import app.lensly.ui.theme.LenslyTheme
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import zxingcpp.BarcodeReader

private const val PASSWORD = "TEST_ONLY_vk7m2qzt"

/** The published BIP39 test mnemonic. It has never held funds and is the only seed this test uses. */
private val TEST_WORDS: List<String> = List(23) { "abandon" } + "art"

private const val WALLET_NAME = "E2E Wallet"

/** The account label the review shows for account 0 of [WALLET_NAME]: its Auto-Name, in capitals. */
private const val ACCOUNT_LABEL = "E2E WALLET 0"

/** A chain that is in no registry, so the review carries the warning and asks for the explicit check. */
private const val UNKNOWN_CHAIN_ID = 999_999L

private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private val ONE_CENT_WEI: BigInteger = BigInteger.TEN.pow(16)

/**
 * The wallet signing flow, end to end, on a device: a wallet is created in the vault, its pairing export is checked, an
 * `eth-sign-request` built for that wallet is fed to the scanner through the real [SignWalletRoute], the review is read
 * and approved with the App Password, and the `eth-signature` that comes back is checked against the request.
 *
 * The route is the production one: its own navigation, its own `SignFlowViewModel`, its own `VaultSignPorts`, and its own
 * screen mapping. The only thing injected is the `FrameSource` at the scan screen, so the CameraX source is replaced by
 * QR images generated in the test. Everything else is production code, and a broken mapping from a step to a screen now
 * fails this test instead of passing it.
 *
 * The wallet is made through the public vault and wallet APIs rather than the create ceremony. The ceremony screens have
 * their own tests, and driving it here would add eight screens of taps before the part this test is about. The request is
 * generated from the wallet the test made, so the from-address check in [app.lensly.app.sign.SignPipeline] is real.
 */
@RunWith(AndroidJUnit4::class)
class WalletSignFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Fixed Argon2 parameters keep the run short. They are a test-only constructor argument, never a stored setting.
    private val vault = Vault(context, RealClock, KdfParams(memoryKiB = 8 * 1024, iterations = 1))
    private var session: VaultSession? = null

    @Before
    fun startFromNoVault() {
        vault.wipe()
    }

    @After
    fun removeVault() {
        session?.close()
        session = null
        vault.wipe()
    }

    @Test
    fun walletSignsAnUnknownChainRequestAndShowsTheSignatureUr() {
        val wallet = createWallet()
        val request = signRequestFor(wallet)

        val holder = VaultHolder(vault)
        holder.adopt(wallet.session, holder.unlockEpoch())

        val probe = BarcodeReader(
            BarcodeReader.Options().apply { formats = setOf(BarcodeReader.Format.QR_CODE) },
        )
        val probeBitmap = qrBitmap(request.parts.first())
        val probeResults = probe.read(probeBitmap, Rect(0, 0, probeBitmap.width, probeBitmap.height))
        assertEquals("the fixture frame must be a scannable QR code", 1, probeResults.size)
        assertEquals(request.parts.first(), probeResults.single().text)

        // The route makes its own view model. Reading it back under the route's own key only observes the step the real
        // screens are on; nothing is injected but the frame source. A route that stopped making this view model would
        // build the factory below instead, and the error in it would fail the run.
        var signFlow: SignFlowViewModel? = null
        compose.setContent {
            LenslyTheme {
                val navController = rememberNavController()
                val source = remember { FixtureFrameSource(request.parts) }
                SignWalletRoute(navController, holder, wallet.id, frameSource = source)
                val created: SignFlowViewModel = viewModel(
                    key = wallet.id,
                    factory = viewModelFactory {
                        initializer { throw AssertionError("the route did not make its sign view model") }
                    },
                )
                signFlow = created
            }
        }
        val flow = requireNotNull(signFlow) { "the route did not make its sign view model" }

        // The injected frames are decoded by the real scanner. A refusal fails the run with its reason, so a broken app
        // cannot pass by timing out.
        val reviewStep = awaitStep(flow, "the review") { it is SignStep.Review || it is SignStep.Refused }
        if (reviewStep is SignStep.Refused) fail("the request was refused: ${reviewStep.reason}")
        val review = (reviewStep as SignStep.Review).ready.review

        assertEquals(wallet.address, review.fromAddress)
        assertEquals(UNKNOWN_CHAIN_ID, review.chainId)
        assertEquals(ACCOUNT_LABEL, review.fromAccountLabel)

        // The screen: an unknown chain warns, and APPROVE waits for the scroll and the explicit check.
        compose.onNodeWithText("NOT IN LENSLY'S REGISTRY").assertExists()
        compose.onNodeWithText("CHAIN $UNKNOWN_CHAIN_ID").assertExists()
        compose.onNodeWithText("Native transfer").assertExists()
        compose.onNodeWithText(ACCOUNT_LABEL).assertExists()

        scrollReviewToTheEnd()
        compose.onNodeWithText("APPROVE").assertIsNotEnabled()
        compose.onNodeWithText("I CHECKED THE CHAIN ID / CONTRACT").performClick()
        compose.onNodeWithText("APPROVE").assertIsEnabled()

        compose.onNodeWithText("APPROVE").performClick()
        compose.onNodeWithText("Enter your App Password to sign.").assertExists()
        typePassword(PASSWORD)
        compose.onNodeWithContentDescription("${PASSWORD.length} characters entered").assertExists()
        compose.onNodeWithContentDescription("Done").performClick()

        val signed = awaitStep(flow, "the signature") { it is SignStep.Signature || it is SignStep.Refused }
        if (signed is SignStep.Refused) fail("the signature was refused: ${signed.reason}")
        val signature = (signed as SignStep.Signature).parts

        compose.onNodeWithText("SIGNATURE").assertExists()
        compose.onNodeWithText("Scan this with your online wallet").assertExists()

        assertSignatureEchoes(request, signature, wallet.address)
    }

    /** Waits for a state that [accept] takes, with the step the flow is on if it never comes. */
    private fun awaitStep(flow: SignFlowViewModel, what: String, accept: (SignStep) -> Boolean): SignStep =
        runBlocking {
            try {
                withTimeout(STEP_TIMEOUT_MS) { flow.state.first(accept) }
            } catch (e: Exception) {
                throw AssertionError("the flow never reached $what; it is ${flow.state.value}", e)
            }
        }

    /** Scrolls the fact list to its end, which is what turns APPROVE on. A review that fits is already at the end. */
    private fun scrollReviewToTheEnd() {
        compose.onNodeWithText("TYPE").performScrollTo()
        // The last row is followed by a spacer, so swipes finish the scroll. The hint says when every fact has been seen.
        repeat(MAX_SWIPES) {
            val seen = compose.onAllNodesWithText("TICK THE CHECK BELOW TO APPROVE").fetchSemanticsNodes()
            if (seen.isNotEmpty()) return
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
    }

    /**
     * Makes a vault, a wallet in it, and checks the pairing export of that wallet. The export is the `ur:crypto-hdkey` an
     * online wallet imports, and it must carry the account public key and chain code the wallet was made with.
     */
    private fun createWallet(): Wallet {
        assertEquals(CreateResult.Created, runBlocking { vault.create(PASSWORD.toCharArray()) })
        val unlocked = runBlocking { vault.unlock(PASSWORD.toCharArray()) }
        assertTrue("the fresh vault did not unlock", unlocked is UnlockResult.Unlocked)
        val opened = (unlocked as UnlockResult.Unlocked).session
        session = opened

        val store = WalletStore(opened)
        val id = store.create(
            name = WALLET_NAME,
            deviceGenerated = false,
            words = TEST_WORDS,
            passphrase = null,
            nowMs = 0L,
        )
        val meta = requireNotNull(store.meta(id)) { "the wallet record must read back" }
        val xpub = requireNotNull(meta.accountXpub) { "a Mnemonic wallet has its account public key" }
        val fingerprint = requireNotNull(meta.masterFingerprintHex) { "a Mnemonic wallet has a fingerprint" }
            .toLong(16)
            .toInt()
        val address = store.addresses(id, 1).single()

        val exportParts = PairingExport.urParts(xpub, fingerprint, WALLET_NAME)
        val exportScanner = UrScanner()
        exportParts.forEach { part -> assertTrue("the pairing part must scan back: $part", exportScanner.receive(part)) }
        val exportUr = requireNotNull(exportScanner.result()) { "the pairing export must form one UR" }
        assertEquals("crypto-hdkey", exportUr.type)
        val exportFields = fieldsOf(exportUr)
        assertArrayEquals(xpub.publicKey, (exportFields[3L] as Cbor.Bytes).value)
        assertArrayEquals(xpub.chainCode, (exportFields[4L] as Cbor.Bytes).value)

        return Wallet(
            session = opened,
            id = id,
            address = address,
            addressBytes = Numeric.hexStringToByteArray(address.removePrefix("0x")),
            fingerprint = fingerprint,
        )
    }

    /**
     * An `eth-sign-request` for [wallet], as the UR frames the scanner will see. It is built here, for the address the test
     * wallet really has, because the pipeline refuses a request for any other address.
     */
    private fun signRequestFor(wallet: Wallet): Request {
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
                                Cbor.Uint(2L) to Cbor.Uint(wallet.fingerprint.toLong() and 0xFFFFFFFFL),
                            ),
                        ),
                    ),
                    Cbor.Uint(6L) to Cbor.Bytes(wallet.addressBytes),
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
        val scanner = UrScanner()
        parts.forEach { part ->
            assertTrue("the signature part must scan back: ${part.take(40)}", scanner.receive(part))
        }
        val ur = requireNotNull(scanner.result()) { "the signature frames did not form a UR" }
        assertEquals("eth-signature", ur.type)

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

    /** The CBOR map of [ur] by its integer key. */
    private fun fieldsOf(ur: UR): Map<Long, Cbor> =
        (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (key, value) -> (key as Cbor.Uint).value to value }

    /** Types [text] on the in-app keyboard, one key at a time, switching pages for digits and symbols. */
    private fun typePassword(text: String) {
        var onLetters = true
        for (c in text) {
            when {
                c.isDigit() || !c.isLetter() -> {
                    if (onLetters) {
                        compose.onNodeWithContentDescription("Symbols").performClick()
                        onLetters = false
                    }
                    compose.onNodeWithContentDescription(c.toString()).performClick()
                }
                c.isUpperCase() -> {
                    if (!onLetters) {
                        compose.onNodeWithContentDescription("Letters").performClick()
                        onLetters = true
                    }
                    // Shift cases every letter key at composition, so the key to press is the capital itself.
                    compose.onNodeWithContentDescription("Shift").performClick()
                    compose.onNodeWithContentDescription(c.toString()).performClick()
                }
                else -> {
                    if (!onLetters) {
                        compose.onNodeWithContentDescription("Letters").performClick()
                        onLetters = true
                    }
                    compose.onNodeWithContentDescription(c.toString()).performClick()
                }
            }
        }
    }

    private class Wallet(
        val session: VaultSession,
        val id: String,
        val address: String,
        val addressBytes: ByteArray,
        val fingerprint: Int,
    )

    private class Request(val requestId: ByteArray, val signData: ByteArray, val parts: List<String>)

    private companion object {
        /** Long enough for Argon2 with the test parameters plus the keystore and file work around it. */
        const val STEP_TIMEOUT_MS = 60_000L

        /** A full swipe per screen, with room to spare, before the review must have reached its end. */
        const val MAX_SWIPES = 6
    }
}
