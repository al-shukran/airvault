package app.lensly.a11y

import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.ui.gate.UnlockScreen
import app.lensly.ui.home.HomeReadiness
import app.lensly.ui.home.WalletHomeScreen
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.sign.TxReview
import app.lensly.ui.sign.TxReviewScreen
import app.lensly.ui.theme.LenslyTheme
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Accessibility Test Framework spike (guideline 1.9, checklist 12): does ATF report Compose nodes on this stack?
 *
 * It does not. The three tests here prove both halves of that answer on a device:
 *
 * 1. [theFrameworkChecksTheViewHierarchy] is the positive control. A plain Android `ImageButton`, 20 px square and
 *    unlabelled, raises `TouchTargetSizeCheck` and `SpeakableTextPresentCheck`, so the framework is armed and the run
 *    can fail.
 * 2. [gateScreenReportsOnlyItsContainer], [homeScreenReportsOnlyItsContainer] and [reviewScreenReportsOnlyItsContainer]
 *    run the unlock gate, the wallet list and the sign review. Each reports one `SpeakableTextPresentCheck` against the
 *    host `AndroidComposeView` and nothing about any node inside it: no touch size, no text contrast, no label. Compose
 *    exposes its nodes through a virtual view provider, which ATF 3.1.2 does not walk.
 * 3. [aBrokenComposeScreenIsNotReported] is the negative control. A 20 dp, unlabelled, clickable Compose box produces
 *    exactly the same single container result, so the silence is the framework's, not the screen's.
 *
 * The test is a tripwire, not a gate. It fails if a Compose or Espresso upgrade starts reporting Compose nodes, which is
 * the point at which ATF becomes worth gating on. Until then the PR lane checks semantics on the JVM
 * (app.lensly.ui.a11y.SemanticsTest) and every release takes a manual TalkBack pass. See docs/testing/accessibility.md.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var validator: AccessibilityValidator

    @Before
    fun armTheFramework() {
        validator = AccessibilityChecks.enable()
            .setRunChecksFromRootView(true)
            // Results are read explicitly below, so the global Espresso assertion must not throw under a test that is
            // measuring where the framework looks.
            .setThrowExceptionForErrors(false)
    }

    @After
    fun disarmTheFramework() {
        AccessibilityChecks.disable()
    }

    @Test
    fun theFrameworkChecksTheViewHierarchy() {
        compose.runOnUiThread {
            val button = ImageButton(compose.activity)
            val root = FrameLayout(compose.activity)
            root.addView(button, FrameLayout.LayoutParams(20, 20))
            compose.activity.setContentView(root)
        }

        val findings = findings()
        log("control_broken_view", findings)
        assertEquals(
            "a 20 px ImageButton with no label must be reported twice",
            listOf("SpeakableTextPresentCheck", "TouchTargetSizeCheck"),
            findings.map { it.check }.sorted(),
        )
    }

    @Test
    fun gateScreenReportsOnlyItsContainer() {
        compose.setContent {
            LenslyTheme {
                UnlockScreen(length = 4, busy = false, onKey = {}, onBackspace = {}, onSubmit = {})
            }
        }

        assertOnlyTheContainerIsReported("gate", findings())
    }

    @Test
    fun homeScreenReportsOnlyItsContainer() {
        compose.setContent {
            LenslyTheme {
                WalletHomeScreen(
                    wallets = listOf(meta("Seed wallet")),
                    recent = emptyList(),
                    readiness = HomeReadiness(
                        networkCount = 1,
                        tokenCount = 1,
                        catalogSequence = null,
                        lockout = FailureCapChoice.DEFAULT,
                    ),
                    nowMs = 0L,
                    onOpen = {},
                    onEdit = {},
                    onPair = {},
                    onCreate = {},
                    onRestore = {},
                    onScan = {},
                    onActivity = {},
                    onWallets = {},
                    onSettings = {},
                    onLock = {},
                    onStartPairing = {},
                    onNetworks = {},
                    onTokens = {},
                    onCatalog = {},
                    onLockout = {},
                    onBackup = {},
                )
            }
        }

        assertOnlyTheContainerIsReported("home", findings())
    }

    @Test
    fun reviewScreenReportsOnlyItsContainer() {
        compose.setContent {
            LenslyTheme {
                TxReviewScreen(review = REVIEW, onApprove = {}, onReject = {})
            }
        }

        assertOnlyTheContainerIsReported("review", findings())
    }

    @Test
    fun aBrokenComposeScreenIsNotReported() {
        compose.setContent {
            LenslyTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // Well under any touch target minimum, and no label at all.
                    Box(Modifier.testTag("too_small_and_unlabelled").size(20.dp).clickable {})
                }
            }
        }

        assertOnlyTheContainerIsReported("broken_compose", findings())
    }

    /**
     * The whole claim of the spike: every real screen and the broken one produce the same single result, against the
     * host view. A finding against anything else means ATF has started reading Compose nodes, and the decision in
     * docs/testing/accessibility.md has to be made again.
     */
    private fun assertOnlyTheContainerIsReported(screen: String, findings: List<Finding>) {
        log(screen, findings)
        assertTrue(
            "$screen: no result at all means the framework stopped running; re-run the spike",
            findings.isNotEmpty(),
        )
        assertEquals(
            "$screen: ATF now reports something other than the host AndroidComposeView. Compose nodes are visible to " +
                "it, so re-run the spike and decide whether ATF should gate",
            listOf("SpeakableTextPresentCheck" to "AndroidComposeView"),
            findings.map { it.check to it.viewClass },
        )
    }

    /** The ERROR and WARNING results from the whole activity, read on the main thread as Espresso would run them. */
    private fun findings(): List<Finding> {
        compose.waitForIdle()
        var results: List<AccessibilityViewCheckResult> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            results = validator.checkAndReturnResults(compose.activity.window.decorView)
        }
        return results
            .filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }
            .map { result ->
                Finding(
                    check = result.accessibilityHierarchyCheck?.simpleName ?: "unknown",
                    viewClass = result.view?.javaClass?.simpleName ?: "none",
                    message = result.message.toString(),
                )
            }
    }

    private fun log(screen: String, findings: List<Finding>) {
        val line = "ATF $screen findings=${findings.size} " +
            findings.joinToString { "${it.check}@${it.viewClass}: ${it.message}" }
        Log.i(TAG, line)
        println(line)
    }

    private fun meta(name: String) = WalletMeta(
        id = name,
        name = name,
        createdAtMs = 0L,
        deviceGenerated = true,
        accountCount = 1,
        masterFingerprintHex = "1a2b3c4d",
        accountXpub = null,
        kind = WalletKind.MNEMONIC,
        autoNameBase = name,
    )

    private class Finding(val check: String, val viewClass: String, val message: String)

    private companion object {
        const val TAG = "A11ySpike"

        /** An unknown-chain transfer, so the review shows its warnings and the chain check as well as the facts. */
        val REVIEW = TxReview(
            networkName = "Monad Testnet",
            chainId = 10143L,
            fromAccountLabel = "MAIN ACCOUNT",
            fromAddress = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F",
            toAddress = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2",
            amount = "0.0100",
            symbol = "MON",
            tokenContract = null,
            function = "Native transfer",
            params = emptyList(),
            fee = listOf("Gas limit" to "21000", "Max fee" to "52.5 gwei"),
            feeNote = "Fee is paid in MON from the sender's balance.",
            warnings = listOf("This address has never received a transaction on this network."),
            nonce = 42L,
            txType = "EIP-1559 (type 2)",
            network = null,
        )
    }
}
