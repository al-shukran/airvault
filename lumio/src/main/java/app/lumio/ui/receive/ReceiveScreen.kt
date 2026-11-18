package app.lumio.ui.receive

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.groupAddress
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslySpacing
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTextAction
import app.lumio.ui.kit.rememberCopyAction
import app.lumio.ui.theme.LumioMono
import app.lumio.ui.theme.LumioRadius

/** The test tag of the address on the receive page. */
const val RECEIVE_ADDRESS_TAG = "receive-address"

/**
 * The page that receives to one account (section 2.1, Receive): the account and its network, the address as a QR code on a white card, and the address
 * in mono, which copies when tapped. [onlySend] says what the network accepts here, and [note] adds a line under it, such as the SPL note. Copy is the
 * primary action and Share the text action.
 */
@Composable
fun ReceiveScreen(
    walletName: String,
    detail: String,
    address: String,
    onlySend: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Receive",
    note: String? = null,
) {
    val context = LocalContext.current
    val copy = rememberCopyAction(value = address, label = "Address")
    LenslyScreen(
        title = title,
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LumioTextAction(text = "Share", onClick = { share(context, address) })
            }
            Spacer(Modifier.height(LenslySpacing.xs))
            LumioPrimaryButton(text = if (copy.copied) "Copied" else "Copy address", onClick = copy.copy)
        },
    ) {
        Text(
            text = walletName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(LumioRadius.lg))
                .background(Color.White)
                .padding(LenslySpacing.md),
        ) {
            AnimatedQr(parts = listOf(address), modifier = Modifier.fillMaxWidth(), keepCase = true)
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = groupAddress(address),
            style = LumioMono,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = copy.copy)
                .testTag(RECEIVE_ADDRESS_TAG),
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = onlySend,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Opens the system share sheet with [address] as its text. */
private fun share(context: Context, address: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
    context.startActivity(Intent.createChooser(send, null))
}
