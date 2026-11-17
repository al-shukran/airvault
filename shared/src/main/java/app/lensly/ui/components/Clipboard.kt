package app.lensly.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** How long a copied value stays on the clipboard before Lensly clears it. */
internal const val CLIPBOARD_CLEAR_DELAY_MS = 60_000L

/** Asks the system to hide the clip from its previews. Android 13 reads it; older systems ignore it. */
private const val IS_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

/** Counts the copies so far, so a clear scheduled for an older copy does nothing once a newer copy is made. */
private var copies = 0

/**
 * The one way Lensly puts text on the clipboard. The clip is marked sensitive, and [CLIPBOARD_CLEAR_DELAY_MS] later it is cleared if it
 * still holds [text] and no newer copy was made. The clear runs on the main looper. Android 10 and later let an app access clipboard data
 * only while it has focus (or is the default input method), so a clear that falls due while Lensly is in the background finds no text
 * to match and does nothing.
 */
internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE_EXTRA, true) }
    clipboard.setPrimaryClip(clip)
    val copy = ++copies
    Handler(Looper.getMainLooper()).postDelayed({
        val held = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (copy == copies && held == text) clipboard.clearPrimaryClip()
    }, CLIPBOARD_CLEAR_DELAY_MS)
}
