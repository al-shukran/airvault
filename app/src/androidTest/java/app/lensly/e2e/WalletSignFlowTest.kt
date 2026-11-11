package app.lensly.e2e

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.flow.ConfirmPasswordScreen
import app.lensly.ui.flow.SignFlow
import app.lensly.ui.flow.SignStep
import app.lensly.ui.flow.VaultSignPorts
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.qr.ScanScreen
import app.lensly.ui.qr.ShowQrScreen
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReviewScreen
import app.lensly.ui.theme.LenslyTheme
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

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
 * `eth-sign-request` built for that wallet is fed to the scanner through [FrameSource], the review is read and approved with
 * the App Password, and the `eth-signature` that comes back is checked against the request.
 *
 * The CameraX source is replaced by QR images generated in the test, so the run needs no camera and no CAMERA permission.
 * Everything else is production code: the wallet store, the sign flow, the decoder, and every screen.
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
        val flow = SignFlow(VaultSignPorts(context, holder, wallet.id))

        compose.setContent {
            LenslyTheme {
                SignHost(flow = flow, frameSource = FixtureFrameSource(request.parts))
            }
        }

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
        compose.onNodeWithText("Chain $UNKNOWN_CHAIN_ID").assertExists()
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

        assertSignatureEchoes(request, signature)
    }

    /** Waits for a state that [accept] takes, with the step the flow is on if it never comes. */
    private fun awaitStep(flow: SignFlow, what: String, accept: (SignStep) -> Boolean): SignStep =
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
        return Request(requestId = requestId, parts = parts)
    }

    /**
     * Asserts that [parts] are the `ur:eth-signature` answer to [request]: the frames form one UR, it echoes the request ID,
     * and it carries a 65-byte signature.
     */
    private fun assertSignatureEchoes(request: Request, parts: List<String>) {
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
        assertEquals(65, (fields[2L] as Cbor.Bytes).value.size)
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
                    compose.onNodeWithContentDescription("Shift").performClick()
                    compose.onNodeWithContentDescription(c.lowercaseChar().toString()).performClick()
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

    private class Request(val requestId: ByteArray, val parts: List<String>)

    private companion object {
        /** Long enough for Argon2 with the test parameters plus the keystore and file work around it. */
        const val STEP_TIMEOUT_MS = 60_000L

        /** A full swipe per screen, with room to spare, before the review must have reached its end. */
        const val MAX_SWIPES = 6
    }
}

/**
 * The sign route's steps, hosted without navigation. It is the same mapping as `SignWalletRoute`, with the frame source
 * passed in and no back stack, so the test drives the real flow and the real screens.
 */
@Composable
private fun SignHost(flow: SignFlow, frameSource: FrameSource) {
    val step by flow.state.collectAsState()
    val scope = rememberCoroutineScope()
    when (val shown = step) {
        is SignStep.Scanning -> ScanScreen(
            title = "SIGN REQUEST",
            progress = shown.progress,
            statusText = shown.status,
            onText = { text -> scope.launch { flow.onScanned(text) } },
            onCancel = flow::leave,
            frameSource = frameSource,
        )
        is SignStep.Refused -> SignRefusedScreen(reason = shown.reason, onDone = flow::leave)
        is SignStep.Review -> TxReviewScreen(
            review = shown.ready.review,
            onApprove = flow::approve,
            onReject = flow::leave,
        )
        is SignStep.Confirming -> ConfirmPasswordStep(busy = shown.busy) { password ->
            scope.launch { flow.confirm(password) }
        }
        is SignStep.Signature -> ShowQrScreen(
            title = "SIGNATURE",
            subtitle = "Scan this with your online wallet",
            parts = shown.parts,
            onDone = flow::leave,
        )
        SignStep.Closed -> Unit
    }
}

/**
 * The App Password step, as `ConfirmPasswordRoute` wires it: the typed characters live in a buffer that is cleared on every
 * submit, and the screen sees only the count.
 */
@Composable
private fun ConfirmPasswordStep(busy: Boolean, onConfirm: (CharArray) -> Unit) {
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        onDispose { buffer.clear() }
    }

    ConfirmPasswordScreen(
        length = length,
        busy = busy,
        onKey = { c ->
            if (!busy && buffer.append(c)) length = buffer.length
        },
        onBackspace = {
            if (!busy) {
                buffer.backspace()
                length = buffer.length
            }
        },
        onSubmit = {
            if (!busy && !buffer.isEmpty()) {
                val password = buffer.toCharArray()
                buffer.clear()
                length = 0
                onConfirm(password)
            }
        },
    )
}
