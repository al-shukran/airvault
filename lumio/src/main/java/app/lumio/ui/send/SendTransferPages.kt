package app.lumio.ui.send

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.lensly.app.isolated
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirStepState
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirTimeline
import app.lumio.ui.kit.ClearAirTimelineStep
import app.lumio.ui.kit.StepThread
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Last Chance, step 6 of the send: the signed transaction waits in memory for a count of [remaining] seconds out of [total], then Lumio sends it. [sending]
 * says the send is out, so the page offers no stop any more, and so does a count that has run out, such as a count of zero seconds. The words say the one
 * part that cannot be undone: after this, no one can.
 */
@Composable
fun SendLastChancePage(
    recipient: String,
    amount: String,
    remaining: Int,
    total: Int,
    sending: Boolean,
    onSendNow: () -> Unit,
    onStop: () -> Unit,
    backLabel: String,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val sendingNow = sending || remaining == 0
    SendPage(
        title = "Send",
        backLabel = backLabel,
        offline = offline,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            ClearAirPrimaryButton(text = "Send Now", onClick = onSendNow, enabled = !sendingNow && offline == null)
            if (offline != null) Text(text = "Sending needs a connection", style = ClearAirType.label, color = palette.ink2)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClearAirTextAction(text = "Stop, Don't Send", onClick = onStop, enabled = !sendingNow)
            }
        },
    ) {
        StepThread(current = STEP_SEND, steps = 5)
        Spacer(Modifier.height(ClearAirSpacing.sm))
        ClearAirEyebrow(text = "Send · 5 of 5")
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.md), contentAlignment = Alignment.Center) {
            CountdownRing(remaining = remaining, total = total)
        }
        Text(text = "Signed by Lensly", style = ClearAirType.titleXL, color = palette.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            text = if (sendingNow) {
                "Sending $amount to $recipient."
            } else {
                "Sending $amount to $recipient in $remaining ${if (remaining == 1) "second" else "seconds"}."
            },
            style = ClearAirType.body,
            color = palette.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.xs),
        )
        Text(
            text = "Changed your mind? This is the last moment you can stop it. After this, no one can.",
            style = ClearAirType.body,
            color = palette.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.sm),
        )
    }
}

/**
 * Lensly Didn't Sign (F10): the owner cancelled before Lensly's reply was scanned, so nothing was sent and the [amount] is still in [fromName]. Its one
 * action goes back to the account. It is the same page for every chain.
 */
@Composable
fun SendDeclinedPage(amount: String, fromName: String, backLabel: String, onBack: () -> Unit, onLock: () -> Unit) {
    SendOutcomePage(
        title = "",
        heading = "Lensly Didn't Sign, and That's Fine",
        mark = "◆",
        body = "Nothing was sent. Your $amount is still in ${isolated(fromName)}.",
        notes = listOf(
            "What Happened" to "You cancelled before Lensly's reply was scanned, so no signature reached Lumio.",
            "What You Can Do" to "Start a new send whenever you're ready. Lensly shows every detail again before it signs.",
        ),
        detail = null,
        primary = "Back to ${isolated(fromName)}",
        onPrimary = onBack,
        secondary = null,
        onSecondary = null,
        backLabel = backLabel,
        onBack = onBack,
        onLock = onLock,
    )
}

/** The countdown as a ring that empties as the seconds pass, with the seconds left in the middle. */
@Composable
private fun CountdownRing(remaining: Int, total: Int) {
    val palette = LocalClearAirPalette.current
    Box(modifier = Modifier.size(RING_SIZE_DP), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = RING_STROKE_DP.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = palette.hairline,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = palette.spark,
                startAngle = -90f,
                sweepAngle = if (total > 0) 360f * remaining.coerceIn(0, total) / total else 0f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(text = remaining.toString(), style = ClearAirType.hero, color = palette.ink)
    }
}

private val RING_SIZE_DP = 132.dp
private val RING_STROKE_DP = 3.dp

/**
 * On Its Way, after the send: the steps of the transfer with the state of each, and the words that say the screen can be closed. [timing] is how
 * long the network usually takes, for the step that is confirming. [signedAtMs] and [sentAtMs] are the clock times of the first two steps, and the
 * confirming step reads "now" while it waits. [arrived] says the transfer has landed: the last two steps are done, the words about
 * closing the screen go, and no step shows a time, since the landing time is not kept. Done goes back to the account; the explorer link is offered when the chain has one.
 */
@Composable
fun SendOnItsWayPage(
    headline: String,
    summary: String,
    network: String,
    timing: String,
    recipient: String,
    signedAtMs: Long,
    sentAtMs: Long,
    arrived: Boolean,
    explorerLabel: String?,
    onExplorer: () -> Unit,
    onDone: () -> Unit,
    backLabel: String,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = "",
        backLabel = backLabel,
        offline = null,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            ClearAirPrimaryButton(text = "Done", onClick = onDone)
            explorerLabel?.let {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ClearAirTextAction(text = it, onClick = onExplorer)
                }
            }
        },
    ) {
        StepThread(current = STEP_SEND, steps = 5)
        Spacer(Modifier.height(ClearAirSpacing.md))
        Text(text = headline, style = ClearAirType.titleXL, color = palette.ink)
        Text(text = summary, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
        Spacer(Modifier.height(ClearAirSpacing.lg))
        ClearAirTimeline(
            steps = listOf(
                ClearAirTimelineStep("Signed on Lensly", "Your keys never left it.", ClearAirStepState.Done, clockOf(signedAtMs)),
                ClearAirTimelineStep("Sent to $network", "The network accepted it.", ClearAirStepState.Done, clockOf(sentAtMs)),
                if (arrived) {
                    ClearAirTimelineStep("Confirming", "Confirmed by the network.", ClearAirStepState.Done)
                } else {
                    ClearAirTimelineStep("Confirming", timing, ClearAirStepState.Now, time = "now")
                },
                if (arrived) {
                    ClearAirTimelineStep("In $recipient", "Funds reached the recipient.", ClearAirStepState.Done)
                } else {
                    ClearAirTimelineStep("In $recipient", "Funds reach the recipient.", ClearAirStepState.Todo)
                },
            ),
        )
        Spacer(Modifier.height(ClearAirSpacing.md))
        if (!arrived) {
            Text(
                text = "You can close this screen. Lumio keeps watching and will tell you when it lands.",
                style = ClearAirType.body,
                color = palette.ink2,
            )
        }
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** The clock time of [epochMs] in [zone], as the timeline shows it: 24 hours, hours and minutes. */
internal fun clockOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.withZone(zone).format(Instant.ofEpochMilli(epochMs))

/**
 * One outcome page of a send that did not go through: Lensly Didn't Sign, the signature for another send, or a failed broadcast. [mark] is the
 * glyph in the ring, [body] says what happened, and [notes] say what happened and what to do. [detail] is the network's own words, when there are
 * any. [primary] and [secondary] are the page's actions.
 */
@Composable
fun SendOutcomePage(
    title: String,
    heading: String,
    mark: String,
    body: String,
    notes: List<Pair<String, String>>,
    detail: String?,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String?,
    onSecondary: (() -> Unit)?,
    backLabel: String,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = title,
        backLabel = backLabel,
        offline = null,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            ClearAirPrimaryButton(text = primary, onClick = onPrimary)
            if (secondary != null && onSecondary != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ClearAirTextAction(text = secondary, onClick = onSecondary)
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .padding(top = ClearAirSpacing.md)
                .size(OUTCOME_MARK_DP)
                .border(1.dp, palette.hairline, RoundedCornerShape(OUTCOME_MARK_DP / 2)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = mark, style = ClearAirType.titleL, color = palette.ink)
        }
        Text(text = heading, style = ClearAirType.titleXL, color = palette.ink, modifier = Modifier.padding(top = ClearAirSpacing.md))
        Text(text = body, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.padding(top = ClearAirSpacing.xs))
        notes.forEach { (label, text) ->
            Column(modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.md)) {
                ClearAirEyebrow(text = label)
                Text(text = text, style = ClearAirType.body, color = palette.ink)
            }
        }
        detail?.let {
            Column(modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.md)) {
                ClearAirEyebrow(text = "Technical Details")
                Text(text = it, style = ClearAirType.address, color = palette.ink2)
            }
        }
    }
}

private val OUTCOME_MARK_DP = 56.dp

/**
 * The one dialog of the send: asked before a chosen recipient is replaced. Change goes to the Recipient page with the amount kept, and the safe answer
 * names the person, so the owner can answer without rereading the page.
 */
@Composable
fun SendChangeRecipientDialog(keepLabel: String, onChange: () -> Unit, onKeep: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Dialog(onDismissRequest = onKeep) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.sheet, RoundedCornerShape(ClearAirSpacing.md))
                .padding(ClearAirSpacing.lg),
        ) {
            Text(text = "Change the Recipient?", style = ClearAirType.titleL, color = palette.ink)
            Text(
                text = "You'll pick someone new. The amount stays.",
                style = ClearAirType.body,
                color = palette.ink2,
                modifier = Modifier.padding(top = ClearAirSpacing.xs),
            )
            Spacer(Modifier.height(ClearAirSpacing.lg))
            ClearAirPrimaryButton(text = "Change", onClick = onChange)
            Box(modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.xs), contentAlignment = Alignment.Center) {
                ClearAirTextAction(text = "Keep $keepLabel", onClick = onKeep)
            }
        }
    }
}

/**
 * Turns the window's brightness to full while the code shows, and gives it back when the page leaves, so the code reads in bright light and the rest
 * of the app keeps the owner's setting.
 */
@Composable
fun BrightnessWhileShown() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        val original = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.setScreenBrightness(FULL_BRIGHTNESS)
        onDispose { window?.setScreenBrightness(original) }
    }
}

private const val FULL_BRIGHTNESS = 1f

private fun Window.setScreenBrightness(value: Float) {
    attributes = attributes.apply { screenBrightness = value }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
