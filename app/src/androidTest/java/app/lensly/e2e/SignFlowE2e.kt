package app.lensly.e2e

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.RealClock
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.instrumentedVault
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.flow.SignFlowViewModel
import app.lensly.ui.flow.SignStep
import app.lensly.ui.flow.SignWalletRoute
import app.lensly.ui.sign.HOLD_TO_SIGN_TAG
import app.lensly.ui.theme.LenslyTheme
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import zxingcpp.BarcodeReader

/** The password the test vault is created with. It protects only the test vault, which is wiped after each test. */
internal const val E2E_PASSWORD = "TEST_ONLY_vk7m2qzt"

/** The published BIP39 test mnemonic. It has never held funds and is the only seed these tests use. */
internal val E2E_WORDS: List<String> = List(23) { "abandon" } + "art"

/** The BIP39 seed of [E2E_WORDS]. The tests derive their expected keys from it, not from the wallet's stored keys. */
internal val E2E_SEED: ByteArray by lazy { Mnemonic.toSeed(E2E_WORDS) }

/** The name of the test wallet. Its account 0 is labelled with it in capitals on the review. */
internal const val E2E_WALLET_NAME = "E2E Wallet"

/** A wallet of the test vault, made from [E2E_WORDS], with the session it was made through and its master fingerprint. */
internal class TestWallet(val session: VaultSession, val id: String, val fingerprint: Int)

/**
 * Makes the test vault, unlocks it and saves the test wallet in it. [addAccounts] then adds the accounts of the chain under test to
 * the wallet, through the same store calls the app makes. Nothing is injected here: the wallet is a real one.
 */
internal fun createTestWallet(vault: Vault, addAccounts: (WalletStore, String) -> Unit): TestWallet {
    assertEquals(CreateResult.Created, runBlocking { vault.create(E2E_PASSWORD.toCharArray()) })
    val unlocked = runBlocking { vault.unlock(E2E_PASSWORD.toCharArray()) }
    assertTrue("the fresh vault did not unlock", unlocked is UnlockResult.Unlocked)
    val session = (unlocked as UnlockResult.Unlocked).session
    val store = WalletStore(session)
    val id = store.create(name = E2E_WALLET_NAME, deviceGenerated = false, words = E2E_WORDS, passphrase = null, nowMs = 0L)
    addAccounts(store, id)
    val meta = requireNotNull(store.meta(id)) { "the wallet record must read back" }
    val fingerprint = requireNotNull(meta.masterFingerprintHex) { "a Mnemonic wallet has a fingerprint" }.toLong(16).toInt()
    return TestWallet(session, id, fingerprint)
}

/** The CBOR map of [ur] by its integer keys. */
internal fun fieldsOf(ur: UR): Map<Long, Cbor> =
    (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (key, value) -> (key as Cbor.Uint).value to value }

/**
 * Reads the one UR that [parts] form, as the online wallet does: every part must scan, and the parts must form a UR of [type].
 * A single frame is a UR of its own, and an animated one is reassembled from its parts in order.
 */
internal fun urOfParts(parts: List<String>, type: String): UR {
    assertTrue("no frames were shown", parts.isNotEmpty())
    val scanner = UrScanner()
    parts.forEach { part -> assertTrue("the part must scan back: ${part.take(40)}", scanner.receive(part)) }
    val ur = requireNotNull(scanner.result()) { "the frames did not form one UR" }
    assertEquals(type, ur.type)
    return ur
}

/**
 * Drives [SignWalletRoute] for one scanned request. The route is the production one, with its own navigation, view model, ports and screen
 * mapping. The only thing injected is the frame source at the scan step, which is fed [frames] in order. Every step waits on the flow's own
 * state, so a step that never comes fails the run with the state the flow is in.
 */
internal class SignRouteDriver(
    private val compose: ComposeContentTestRule,
    private val vault: Vault,
    private val wallet: TestWallet,
    private val frames: List<String>,
) {
    private lateinit var flow: SignFlowViewModel

    /** Shows the route for the wallet, and reads back the view model it made. */
    fun open() {
        val holder = VaultHolder(vault)
        holder.adopt(wallet.session, holder.unlockEpoch())
        checkScannable(frames.first())
        // The route makes its own view model. Reading it back under the route's own key observes the step the real screens are on.
        // A route that stopped making this view model would build the factory below instead, and its error would fail the run.
        var signFlow: SignFlowViewModel? = null
        compose.setContent {
            LenslyTheme {
                val navController = rememberNavController()
                val source = remember { FixtureFrameSource(frames) }
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
        flow = requireNotNull(signFlow) { "the route did not make its sign view model" }
    }

    /**
     * Waits for the review that [isReview] accepts. A refusal fails the run with the reason the flow gave, so a broken app cannot pass by
     * timing out.
     */
    fun awaitReview(isReview: (SignStep) -> Boolean): SignStep {
        val step = awaitStep("the review") { isReview(it) || it is SignStep.Refused }
        if (step is SignStep.Refused) fail("the request was refused: ${step.reason}")
        return step
    }

    /** Whether the hold-to-sign control is enabled, so the review can be approved as it stands. */
    fun approvable(): Boolean = runCatching { compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled() }.isSuccess

    /**
     * APPROVE: holds the hold-to-sign control until the flow is at [confirming], types the App Password, taps Done, and returns the
     * signature frames the flow shows. The screen checks are the ones a signature must pass on the device.
     */
    fun approveAndSign(confirming: (SignStep) -> Boolean): List<String> {
        holdSignControlUntil(confirming)
        compose.onNodeWithText("Enter your Application Password to sign.").assertExists()
        typePassword(E2E_PASSWORD)
        compose.onNodeWithContentDescription("${E2E_PASSWORD.length} characters entered").assertExists()
        compose.onNodeWithContentDescription("Done").performClick()

        val signed = awaitStep("the signature") { it is SignStep.Signature || it is SignStep.Refused }
        if (signed is SignStep.Refused) fail("the signature was refused: ${signed.reason}")
        compose.onNodeWithText("SIGNATURE").assertExists()
        compose.onNodeWithText("Scan this with your online wallet").assertExists()
        return (signed as SignStep.Signature).parts
    }

    /** Waits for a state that [accept] takes, with the step the flow is on if it never comes. */
    fun awaitStep(what: String, accept: (SignStep) -> Boolean): SignStep = runBlocking {
        try {
            withTimeout(STEP_TIMEOUT_MS) { flow.state.first(accept) }
        } catch (e: Exception) {
            throw AssertionError("the flow never reached $what; it is ${flow.state.value}", e)
        }
    }

    /**
     * Holds a real touch on the hold-to-sign control until [confirming] is met, then lifts it.
     *
     * A `performTouchInput` gesture is released when its block returns, so a press split across the wait is lifted at once
     * and the control cancels the hold. The touch is injected through [UiAutomation] instead, so the finger stays down while
     * the bar fills on the frame clock.
     *
     * The first touch after the control is enabled starts its pointer handler and is not delivered to it. A tap that lets
     * go at once signs nothing, and it leaves the handler waiting for the press that follows.
     */
    private fun holdSignControlUntil(confirming: (SignStep) -> Boolean) {
        compose.waitForIdle()
        val center = compose.onNodeWithTag(HOLD_TO_SIGN_TAG).fetchSemanticsNode().boundsInWindow.center
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val tapTime = SystemClock.uptimeMillis()
        injectTouch(automation, MotionEvent.ACTION_DOWN, tapTime, center.x, center.y)
        injectTouch(automation, MotionEvent.ACTION_UP, tapTime, center.x, center.y)
        compose.waitForIdle()
        val downTime = SystemClock.uptimeMillis()
        injectTouch(automation, MotionEvent.ACTION_DOWN, downTime, center.x, center.y)
        try {
            awaitStep("the Application Password step", confirming)
        } finally {
            // The hold has just opened the keyboard under this point, and a lone UP there types a key. A cancel ends the
            // touch without a tap.
            injectTouch(automation, MotionEvent.ACTION_CANCEL, downTime, center.x, center.y)
            compose.waitForIdle()
        }
    }

    private fun injectTouch(automation: UiAutomation, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            check(automation.injectInputEvent(event, true)) { "the touch event was not injected" }
        } finally {
            event.recycle()
        }
    }

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

    /** The first frame goes through the same QR decoder a camera would use, so a bad fixture fails here and not as a timeout. */
    private fun checkScannable(text: String) {
        val probe = BarcodeReader(BarcodeReader.Options().apply { formats = setOf(BarcodeReader.Format.QR_CODE) })
        val bitmap = qrBitmap(text)
        val results = probe.read(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        assertEquals("the fixture frame must be a scannable QR code", 1, results.size)
        assertEquals(text, results.single().text)
    }

    private companion object {
        /** Long enough for Argon2 with the test parameters plus the keystore and file work around it. */
        const val STEP_TIMEOUT_MS = 60_000L
    }
}

/**
 * The set-up every sign-flow test shares: the test vault is wiped before and after each test, and one compose rule hosts the route.
 * A test calls [openWallet] for its wallet and [driverFor] for its request.
 */
abstract class SignFlowE2eBase {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Fixed Argon2 parameters keep the run short. They are a test-only constructor argument, never a stored setting.
    protected val vault = instrumentedVault(context, RealClock, KdfParams(memoryKiB = 8 * 1024, iterations = 1))

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

    /** Makes the test wallet with [addAccounts], and keeps its session to close after the test. */
    internal fun openWallet(addAccounts: (WalletStore, String) -> Unit): TestWallet =
        createTestWallet(vault, addAccounts).also { session = it.session }

    /** The route driver for [wallet], fed [frames] as the request the scanner reads. */
    internal fun driverFor(wallet: TestWallet, frames: List<String>): SignRouteDriver =
        SignRouteDriver(compose, vault, wallet, frames)
}
