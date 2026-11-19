package app.lumio.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.lensly.ui.components.copyToClipboard
import kotlinx.coroutines.delay

/** How long a copy control reads "Copied" after a copy (section 2.1). */
private const val COPIED_MS = 1_500L

/** A copy control's state: [copied] is true for 1.5 s after a copy, and [copy] puts the value on the clipboard. */
class CopyAction internal constructor(val copied: Boolean, val copy: () -> Unit)

/**
 * The copy control of [value], which the clipboard holds under [label]. A copy fires the copy haptic (section 3.5), and [CopyAction.copied] reads true
 * for 1.5 s, so the caller can morph its label to "Copied".
 */
@Composable
fun rememberCopyAction(value: String, label: String): CopyAction {
    val context = LocalContext.current
    val haptics = rememberLumioHaptics()
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copies) {
        if (copies > 0) {
            copied = true
            delay(COPIED_MS)
            copied = false
        }
    }
    return CopyAction(
        copied = copied,
        copy = {
            copyToClipboard(context, label, value)
            haptics.copy()
            copies++
        },
    )
}
