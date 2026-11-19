package app.lensly.ui.qr

import android.content.Context
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestParser
import app.lensly.app.EVM_FAMILY
import app.lensly.app.HideDelay
import app.lensly.app.RegistryHolder
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.SignPipeline
import app.lensly.app.sign.WalletView
import app.lensly.core.parse.InProcessParser
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.applySecureWindow
import app.lensly.ui.settings.Catalog
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.settings.HideDelayScreen
import app.lensly.ui.settings.SettingsScreen
import app.lensly.ui.sign.ReviewScreen
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.theme.LumenTheme


/**
 * Debug-only harness that hosts the QR and signing screens with fake data, so they can be checked
 * on a device without the vault.
 *
 * The activity is not exported, so `adb shell am start` is refused with a permission denial. Start it from inside the app's own
 * process, with the "screen" string extra set to one of show1, show12, scan, review-typed, refused-typed, refused, settings or hide-delay.
 *
 * show1 and show12 animate one or twelve fake UR parts. scan opens the live camera and writes each
 * decoded text into the status line (and its length to logcat under the tag [TAG]).
 * settings the real Settings screen with sample values, including the dice and coin hide delay rows,
 * hide-delay the hide delay editor, interactive, with sample values,
 * review-typed a Permit (ERC-2612) typed-data request
 * built by the sign pipeline from the shipped registry: its denylist warning and confirmation are shown. refused-typed a typed-data
 * request on a chain outside the registry, which the pipeline refuses before review. refused a refusal, all with sample data.
 */
class QrPreviewActivity : ComponentActivity() {

    private val screen = mutableStateOf(SCREEN_SHOW1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()
        screen.value = screenFrom(intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        setContent {
            val current by screen
            LumenTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    when (current) {
                        SCREEN_SHOW12 -> ShowPreview(title = "SIGNATURE", parts = sampleTwelveParts(), onDone = ::finish)
                        SCREEN_SCAN -> ScanPreview(onFinish = ::finish)
                        SCREEN_REVIEW_TYPED -> TypedPermitPreview()
                        SCREEN_REFUSED_TYPED -> TypedRefusedPreview(onDone = ::finish)
                        SCREEN_REFUSED -> SignRefusedScreen(
                            reason = "Chain ID mismatch. The request targets chain 1, but this vault is set to chain 10143.",
                            onDone = ::finish,
                        )
                        SCREEN_SETTINGS -> SettingsPreview(onBack = ::finish)
                        SCREEN_HIDE_DELAY -> HideDelayPreview(onBack = ::finish)
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
            SCREEN_REVIEW_TYPED -> SCREEN_REVIEW_TYPED
            SCREEN_REFUSED_TYPED -> SCREEN_REFUSED_TYPED
            SCREEN_REFUSED -> SCREEN_REFUSED
            SCREEN_SETTINGS -> SCREEN_SETTINGS
            SCREEN_HIDE_DELAY -> SCREEN_HIDE_DELAY
            else -> SCREEN_SHOW1
        }

    companion object {
        const val TAG = "QrPreview"
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_SHOW1 = "show1"
        const val SCREEN_SHOW12 = "show12"
        const val SCREEN_SCAN = "scan"
        const val SCREEN_REVIEW_TYPED = "review-typed"
        const val SCREEN_REFUSED_TYPED = "refused-typed"
        const val SCREEN_REFUSED = "refused"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_HIDE_DELAY = "hide-delay"
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
            Log.d(QrPreviewActivity.TAG, "decoded ${text.length} chars")
            status = "Decoded ${text.length} chars: ${text.take(160)}"
        },
        onCancel = onFinish,
    )
}

@Composable
private fun SettingsPreview(onBack: () -> Unit) {
    SettingsScreen(
        bulkCap = BulkCap.DEFAULT,
        onBulkCap = {},
        onChangePassword = {},
        failureDelayCap = FailureCapChoice.DEFAULT,
        onFailureDelayCap = {},
        catalog = Catalog(networks = emptyList(), tokens = emptyList()),
        onNetworks = {},
        onTokens = {},
        proxyPolicy = ProxyPolicy.DEFAULT,
        hideDelay = HideDelay.DEFAULT,
        onHideDelay = {},
        onUpdateCatalog = {},
        onWipe = {},
        versionName = "preview",
        versionCode = 0,
        onBack = onBack,
    )
}

/** The hide delay screen with the default range. Each number applies as it is typed, while the range stays one Settings accepts. */
@Composable
private fun HideDelayPreview(onBack: () -> Unit) {
    var shortest by remember { mutableStateOf<Int?>(HideDelay.DEFAULT.minMs.toInt()) }
    var longest by remember { mutableStateOf<Int?>(HideDelay.DEFAULT.maxMs.toInt()) }
    HideDelayScreen(
        shortest = shortest,
        longest = longest,
        onShortest = { shortest = it },
        onLongest = { longest = it },
        onBack = onBack,
    )
}

/** A Permit (ERC-2612) request, reviewed by the sign pipeline as a scanned request is. The shipped denylist warns on it. */
@Composable
private fun TypedPermitPreview() {
    val context = LocalContext.current
    val ready = remember(context) { typedPermitSampleReady(context) }
    ReviewScreen(
        review = ready.review,
        signer = ready.signer,
        addressNotes = ready.addressNotes,
        addressChain = EVM_FAMILY,
        onApprove = { Toast.makeText(context, "Preview: approved", Toast.LENGTH_SHORT).show() },
        onReject = { Toast.makeText(context, "Preview: rejected", Toast.LENGTH_SHORT).show() },
    )
}

/** The refusal the sign pipeline gives a typed message for a chain that the registry does not hold. */
@Composable
private fun TypedRefusedPreview(onDone: () -> Unit) {
    val context = LocalContext.current
    val reason = remember(context) { typedUnknownChainRefusal(context) }
    SignRefusedScreen(reason = reason, onDone = onDone)
}

/**
 * The ready review of a sample Permit on chain 1, built by the sign pipeline from the shipped registry. The shipped denylist holds a WARN
 * entry for Permit, so the review shows its reason and needs the risky-request confirmation before HOLD TO SIGN.
 */
private fun typedPermitSampleReady(context: Context): Prepared.Ready =
    when (val prepared = samplePermitPrepared(context, chainId = 1)) {
        is Prepared.Ready -> prepared
        is Prepared.Refused -> error("The sample Permit was refused: ${prepared.reason}")
    }

/** The refusal the pipeline gives the same Permit on chain 999999, which the registry does not hold. */
private fun typedUnknownChainRefusal(context: Context): String =
    when (val prepared = samplePermitPrepared(context, chainId = 999_999)) {
        is Prepared.Refused -> prepared.reason
        is Prepared.Ready -> error("The sample Permit on an unknown chain was reviewed, not refused")
    }

/** A sample Permit on [chainId], parsed and checked by the sign pipeline the way a scanned request is. */
private fun samplePermitPrepared(context: Context, chainId: Long): Prepared {
    val wallet = HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art"))
    val view = WalletView(
        id = "preview-wallet",
        masterFingerprint = wallet.masterFingerprint,
        accountCount = 1,
        autoNameBase = "Preview",
        addressAt = { wallet.address(it) },
    )
    val owner = wallet.address(0)
    val json = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
        """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],""" +
        """"Permit":[{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},""" +
        """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]},"primaryType":"Permit",""" +
        """"domain":{"name":"USD Coin","version":"2","chainId":$chainId,""" +
        """"verifyingContract":"0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"},""" +
        """"message":{"owner":"$owner","spender":"0x1111111111111111111111111111111111111111","value":"25000000",""" +
        """"nonce":"0","deadline":"1900000000"}}"""
    val request = EthSignRequest(
        requestId = ByteArray(16) { (it + 1).toByte() },
        signData = json.toByteArray(Charsets.UTF_8),
        dataType = DataType.TYPED_DATA,
        chainId = chainId,
        derivationPath = listOf(
            PathStep(44, true),
            PathStep(60, true),
            PathStep(0, true),
            PathStep(0, false),
            PathStep(0, false),
        ),
        sourceFingerprint = wallet.masterFingerprint,
        address = null,
        origin = "sample.example",
    )
    return SignPipeline(RegistryHolder.get(context), InProcessParser).prepare(EvmRequestParser.parsedOf(request), view)
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
