package app.lensly.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock

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
 * to match and does nothing. [clearStaleCopy] clears such a copy when Lensly gains focus again.
 */
internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE_EXTRA, true) }
    clipboard.setPrimaryClip(clip)
    val copy = ++copies
    lastCopy = CopiedText(text, SystemClock.elapsedRealtime())
    Handler(Looper.getMainLooper()).postDelayed({
        val held = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (copy == copies && held == text) clipboard.clearPrimaryClip()
    }, CLIPBOARD_CLEAR_DELAY_MS)
}

/** The last copy Lensly made, and when. [clearStaleCopy] checks it when Lensly gains focus. */
private class CopiedText(val text: String, val copiedAtMs: Long)

private var lastCopy: CopiedText? = null

/** What a focus check does with the last copy: keep watching it, clear it, or forget it. */
internal enum class StaleCopyAction { KeepWatching, Clear, Forget }

/**
 * Decides what to do with a copy made at [copiedAtMs], checked at [nowMs], while the clipboard holds [heldText] (null when it cannot be
 * read from here). A copy still inside [CLIPBOARD_CLEAR_DELAY_MS] is kept, and so is one that cannot be read, for the next check. An old
 * copy that is still on the clipboard is cleared. A clipboard that holds something else no longer holds the copy, so it is forgotten.
 */
internal fun staleCopyAction(copiedAtMs: Long, nowMs: Long, copiedText: String, heldText: String?): StaleCopyAction = when {
    nowMs - copiedAtMs < CLIPBOARD_CLEAR_DELAY_MS -> StaleCopyAction.KeepWatching
    heldText == null -> StaleCopyAction.KeepWatching
    heldText == copiedText -> StaleCopyAction.Clear
    else -> StaleCopyAction.Forget
}

/**
 * Applies [staleCopyAction] to the last copy. The clear that falls due while Lensly is in the background cannot read the clipboard, so
 * this runs when the window gains focus, and the clipboard can be read and cleared again.
 */
internal fun clearStaleCopy(context: Context) {
    val copy = lastCopy ?: return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val held = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    when (staleCopyAction(copy.copiedAtMs, SystemClock.elapsedRealtime(), copy.text, held)) {
        StaleCopyAction.KeepWatching -> Unit
        StaleCopyAction.Clear -> {
            lastCopy = null
            clipboard.clearPrimaryClip()
        }
        StaleCopyAction.Forget -> lastCopy = null
    }
}
