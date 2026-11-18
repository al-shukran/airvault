package app.lensly.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletWarningChip

/** The height of the Home top bar. */
private val HEADER_HEIGHT = 56.dp

/** The test tag of the Home top bar's lock button. */
const val HOME_LOCK_TAG = "home-lock"

/**
 * The top bar of an app's Home: the [wordmark] in the dot-matrix headline face on the left, and the lock as an icon button on the right.
 * It has no back button, since Home is the top of the app. A debug build that runs on a software keystore shows its warning as a chip
 * beside the wordmark, so the content keeps its place. A [mark], when the app has one, sits before the wordmark.
 */
@Composable
fun HomeTopBar(wordmark: String, onLock: () -> Unit, debugNotice: String? = null, mark: (@Composable () -> Unit)? = null) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(horizontal = WALLET_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) {
            mark()
            Spacer(Modifier.width(LenslySpacing.sm))
        }
        Text(
            text = wordmark,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (debugNotice != null) {
            Spacer(Modifier.width(LenslySpacing.sm))
            WalletWarningChip(text = debugNotice)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onLock,
            modifier = Modifier.testTag(HOME_LOCK_TAG).tapHaptic(haptics),
        ) {
            Icon(imageVector = Icons.Filled.Lock, contentDescription = "Lock the vault", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
