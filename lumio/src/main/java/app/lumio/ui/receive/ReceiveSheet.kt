package app.lumio.ui.receive

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.qr.QrMatrix
import app.lensly.ui.qr.QrPixels
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.receive.ReceiveStep
import app.lumio.receive.receiveNote
import app.lumio.receive.receiveShareText
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ClearAirAddressGrid
import app.lumio.ui.kit.ClearAirAlertNote
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.rememberCopyAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The side of the code on the sheet, the proposal's 220 dp, with its card's corner and padding. */
private val QR_SIZE = 220.dp
private val QR_CORNER = 24.dp
private val QR_PADDING = 16.dp

/** The logo at the code's centre, and the white disc behind it that keeps the modules around it clear. */
private val LOGO_SIZE = 44.dp
private val LOGO_DISC = 52.dp

/** The code's pixels on the sheet, and its white quiet zone in modules: the card's padding gives the rest of it. */
private const val SCREEN_PIXELS = 384
private const val SCREEN_MARGIN = 1

/**
 * The last step of a Receive (H3): the network, the coin and the account, the code of the address with the coin's logo at its centre, the address in
 * groups of four, the warning for the network, and Copy and Share. Copy is the primary action, since people copy far more often than they share. Share
 * sends the code as an image with the sentence of [receiveShareText]. [onBack] goes one step back, and it is null when no step came before.
 */
@Composable
internal fun ReceiveSheetStep(step: ReceiveStep.Receive, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalClearAirPalette.current
    val address = step.account.address
    val symbol = step.coin.symbol
    val network = step.network.label
    val copy = rememberCopyAction(value = address, label = "Address")
    ReceiveSheetFrame(
        bottom = {
            ClearAirPrimaryButton(text = if (copy.copied) "Copied ✓" else "Copy Address", onClick = copy.copy)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClearAirTextAction(
                    text = "Share",
                    onClick = {
                        val sentence = receiveShareText(symbol, address, network)
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) { writeReceiveCode(context, address) }
                            shareReceive(context, uri, sentence)
                        }
                    },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (onBack != null) {
                ClearAirIconButton(
                    glyph = ClearAirGlyph.BACK,
                    label = "Back",
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ClearAirEyebrow(text = network)
                Text(
                    text = "Receive $symbol",
                    style = ClearAirType.titleL,
                    color = palette.ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Into ${step.account.name} · only you see this name",
                    style = ClearAirType.label.copy(fontWeight = FontWeight.Normal),
                    color = palette.ink2,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(ClearAirSpacing.md))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ReceiveCodeCard(address = address, icon = step.coin.icon, description = "QR code of your $symbol address")
        }
        Spacer(Modifier.height(ClearAirSpacing.md))
        ClearAirEyebrow(text = "Your $symbol Address")
        ClearAirAddressGrid(address = address)
        Spacer(Modifier.height(ClearAirSpacing.md))
        ClearAirAlertNote(text = receiveNote(network))
    }
}

/** The code of [address] on a white card, with the logo of [icon] at its centre. The code is black on white, as scanners expect. */
@Composable
private fun ReceiveCodeCard(address: String, icon: AssetIconSpec, description: String) {
    val palette = LocalClearAirPalette.current
    val code: ImageBitmap = remember(address) {
        QrMatrix.pixelsForCentredLogo(address, margin = SCREEN_MARGIN, targetPx = SCREEN_PIXELS).toImageBitmap()
    }
    Box(
        modifier = Modifier
            .size(QR_SIZE)
            .clip(RoundedCornerShape(QR_CORNER))
            .background(Color.White)
            .border(1.dp, palette.hairline, RoundedCornerShape(QR_CORNER))
            .padding(QR_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = code,
            contentDescription = description,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.size(LOGO_DISC).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            AssetIcon(spec = icon, size = LOGO_SIZE)
        }
    }
}

private fun QrPixels.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
