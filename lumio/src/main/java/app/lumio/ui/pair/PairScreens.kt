package app.lumio.ui.pair

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.app.export.PairingPayload
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.openAppSettings
import app.lensly.ui.qr.DECODE_FAILED_TEXT
import app.lensly.ui.qr.QrScanner
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.net.Networks
import app.lumio.pair.PairImport
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirPageTopBar
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.scan.rememberCameraAccess

/**
 * The camera scan for a pairing code: the Pair with Lensly page, with the live camera in its frame. [progress] fills the bar for an animated code, [status]
 * says what the scan is doing, and [complete] shows the check. The page draws the whole frame, so the scanner draws none of its own: while the scanner
 * cannot read, the status line says so in place of [status]. [frameSource] is for tests, which pass their own frames.
 */
@Composable
fun PairScanScreen(
    chrome: PairChrome,
    progress: Float?,
    status: String,
    onText: (String) -> Unit,
    complete: Boolean = false,
    frameSource: FrameSource? = null,
) {
    val camera = rememberCameraAccess(frameSource)
    val context = LocalContext.current
    val denied = camera.refused && !camera.granted
    var unreadable by remember { mutableStateOf(false) }
    PairWithLensly(
        chrome = chrome,
        progress = progress,
        status = if (unreadable) DECODE_FAILED_TEXT else status,
        complete = complete,
        denied = denied,
        onOpenSettings = { context.openAppSettings() },
    ) { viewport ->
        if (camera.granted) QrScanner(onText = onText, modifier = viewport, frameSource = frameSource, viewfinder = false, onDecodeFailed = { unreadable = it })
    }
}

/**
 * The review of a scanned pairing, before anything is saved (I11, "Add Air 1?"): the wallet's name, one row per app with its count, and the address
 * book. Only public addresses and names cross from Lensly, and saving keeps them behind the Lumio password. [alreadyPaired] means saving replaces what
 * Lumio holds for the wallet. [error] is shown when a save failed.
 */
@Composable
fun PairReviewScreen(
    pairing: PairingPayload,
    alreadyPaired: Boolean,
    error: String?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val ethereum = PairImport.accountsOf(pairing, Networks.EIP155).size
    val bitcoin = PairImport.bitcoinAccountsOf(pairing).size
    val solana = PairImport.solanaPublicKeysOf(pairing).size
    val algorand = PairImport.algorandPublicKeysOf(pairing).size
    Column(modifier = Modifier.fillMaxSize()) {
        ClearAirPageTopBar(title = "Pair with Lensly", backLabel = "Scan", onBack = onCancel, onLock = onLock)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
                ClearAirEyebrow(text = "Pair · 2 of 2")
                Spacer(Modifier.height(ClearAirSpacing.xs))
                Text(text = "Add ${pairing.name}?", style = ClearAirType.titleXL, color = palette.ink)
                Text(text = "Read from Lensly just now", style = ClearAirType.body, color = palette.ink2)
                Spacer(Modifier.height(ClearAirSpacing.lg))
            }
            if (ethereum > 0) AppRow(app = "Ethereum App", value = countOf(ethereum, "account"))
            if (bitcoin > 0) AppRow(app = "Bitcoin App", value = countOf(bitcoin, "account"))
            if (solana > 0) AppRow(app = "Solana App", value = countOf(solana, "account"))
            if (algorand > 0) AppRow(app = "Algorand App", value = countOf(algorand, "account"))
            if (pairing.addressBook.isNotEmpty()) {
                AppRow(app = "Address Book", value = countOf(pairing.addressBook.size, "entry", "entries"))
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
                Spacer(Modifier.height(ClearAirSpacing.lg))
                Text(text = "Public addresses and names only", style = ClearAirType.body, color = palette.ink)
                Text(text = "Saved behind your Lumio password", style = ClearAirType.body, color = palette.ink)
                Spacer(Modifier.height(ClearAirSpacing.md))
                Text(
                    text = "Check that the wallet name matches the one Lensly shows. Your keys stay on Lensly.",
                    style = ClearAirType.body,
                    color = palette.ink2,
                )
                if (alreadyPaired) {
                    Spacer(Modifier.height(ClearAirSpacing.md))
                    Text(
                        text = "This wallet is already paired. Saving makes its names, labels, groups and accounts match Lensly.",
                        style = ClearAirType.body,
                        color = palette.ink2,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(ClearAirSpacing.md))
                    Text(text = it, style = ClearAirType.body, color = palette.rose)
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClearAirPrimaryButton(text = if (alreadyPaired) "Update" else "Save Wallet", onClick = onSave)
            Spacer(Modifier.height(ClearAirSpacing.xs))
            ClearAirTextAction(text = "Cancel", onClick = onCancel)
        }
    }
}

/** One app of the pairing with its count: the name on the left, the count in ink beside it. */
@Composable
private fun AppRow(app: String, value: String) {
    val palette = LocalClearAirPalette.current
    ClearAirRow(
        title = app,
        trailing = { Text(text = value, style = ClearAirType.rowValue, color = palette.ink2) },
    )
}

/** "1 account", "2 accounts": the count of [noun], or of [plural] when it is not one. */
private fun countOf(count: Int, noun: String, plural: String = "${noun}s"): String = "$count ${if (count == 1) noun else plural}"
