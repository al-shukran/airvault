package app.lensly.ui.qr

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lensly.ui.applySecureWindow
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReview
import app.lensly.ui.sign.TxReviewScreen
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme

/**
 * Debug-only harness that hosts the QR and signing screens with fake data, so they can be checked
 * on a device without the vault.
 *
 * Launch with the "screen" string extra set to one of show1, show12, scan, review or refused:
 *   adb shell am start -n app.lensly/app.lensly.ui.qr.QrPreviewActivity --es screen show12
 *
 * show1 and show12 animate one or twelve fake UR parts. scan opens the live camera and writes each
 * decoded text into the status line (and to logcat under the tag [TAG]). review and refused show
 * the signing screens with sample data.
 */
class QrPreviewActivity : ComponentActivity() {

    private val screen = mutableStateOf(SCREEN_SHOW1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()
        screen.value = screenFrom(intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
        )

        setContent {
            val current by screen
            LenslyTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LenslyBlack),
                ) {
                    when (current) {
                        SCREEN_SHOW12 -> ShowPreview(title = "SIGNATURE", parts = sampleTwelveParts(), onDone = ::finish)
                        SCREEN_SCAN -> ScanPreview(onFinish = ::finish)
                        SCREEN_REVIEW -> ReviewPreview()
                        SCREEN_REFUSED -> SignRefusedScreen(
                            reason = "Chain ID mismatch. The request targets chain 1, but this vault is set to chain 10143.",
                            onDone = ::finish,
                        )
                        else -> ShowPreview(title = "PAIR WALLET", parts = listOf(SAMPLE_SINGLE_PART), onDone = ::finish)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screen.value = screenFrom(intent)
    }

    private fun screenFrom(intent: Intent): String =
        when (intent.getStringExtra(EXTRA_SCREEN)) {
            SCREEN_SHOW12 -> SCREEN_SHOW12
            SCREEN_SCAN -> SCREEN_SCAN
            SCREEN_REVIEW -> SCREEN_REVIEW
            SCREEN_REFUSED -> SCREEN_REFUSED
            else -> SCREEN_SHOW1
        }

    companion object {
        const val TAG = "QrPreview"
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_SHOW1 = "show1"
        const val SCREEN_SHOW12 = "show12"
        const val SCREEN_SCAN = "scan"
        const val SCREEN_REVIEW = "review"
        const val SCREEN_REFUSED = "refused"
    }
}

@Composable
private fun ShowPreview(title: String, parts: List<String>, onDone: () -> Unit) {
    ShowQrScreen(
        title = title,
        subtitle = "Scan with Rabby or MetaMask",
        parts = parts,
        onDone = onDone,
    )
}

@Composable
private fun ScanPreview(onFinish: () -> Unit) {
    var status by remember { mutableStateOf("Point the camera at a QR code") }
    ScanScreen(
        title = "SCAN",
        progress = null,
        statusText = status,
        onText = { text ->
            Log.d(QrPreviewActivity.TAG, "decoded ${text.length} chars: $text")
            status = "Decoded ${text.length} chars: ${text.take(160)}"
        },
        onCancel = onFinish,
    )
}

@Composable
private fun ReviewPreview() {
    val context = LocalContext.current
    TxReviewScreen(
        review = sampleReview,
        onApprove = { Toast.makeText(context, "Preview: approved", Toast.LENGTH_SHORT).show() },
        onReject = { Toast.makeText(context, "Preview: rejected", Toast.LENGTH_SHORT).show() },
    )
}

private const val SAMPLE_SINGLE_PART =
    "UR:CRYPTO-ACCOUNT/1-1/LPADAXCFAXHDCLAOEYWTJSTYLKSEHDIEUTVDHEKNUECSLNCXLTBBUTZTHDLEHTCMOHGUMWDMCPTDOSVTSSFA"

/** Twelve fake UR fragments of a PSBT-sized payload. Each part is a different string. */
private fun sampleTwelveParts(): List<String> {
    val alphabet = "ABCDEF0123456789"
    return (0 until 12).map { part ->
        val body = (0 until 200).map { i -> alphabet[(i * 7 + part * 13) % alphabet.length] }
        "UR:CRYPTO-PSBT/${part + 1}-12/" + body.joinToString("")
    }
}

private val sampleReview = TxReview(
    networkName = "Monad Testnet",
    chainId = 10143L,
    fromAccountLabel = "Main account",
    fromAddress = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F",
    toAddress = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2",
    amount = "0.0100",
    symbol = "MON",
    tokenContract = null,
    function = "Native transfer",
    params = emptyList(),
    fee = listOf(
        "Gas limit" to "21000",
        "Max fee" to "52.5 gwei",
        "Priority fee" to "2 gwei",
        "Max total" to "0.0011 MON",
    ),
    feeNote = "Fee is paid in MON from the sender's balance.",
    warnings = listOf("This address has never received a transaction on this network."),
    nonce = 42L,
    txType = "EIP-1559 (type 2)",
)
