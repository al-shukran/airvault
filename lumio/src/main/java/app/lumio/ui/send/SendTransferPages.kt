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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
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
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
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
        SendStepper(current = STEP_SEND)
        SendStepLabel(name = "Send", number = STEP_SEND + 1)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
            CountdownRing(remaining = remaining, total = total)
        }
        SendHeading(text = "Signed by Lensly", top = 22.dp, bottom = 0.dp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            text = if (sendingNow) {
                "Sending $amount to $recipient."
            } else {
                "Sending $amount to $recipient in $remaining ${if (remaining == 1) "second" else "seconds"}."
            },
            style = ClearAirType.body,
            color = palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.xs),
        )
        Text(
            text = "Changed your mind? This is the last moment you can stop it. After this, no one can.",
            style = ClearAirType.small,
            color = palette.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.xxs),
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
        heading = "Lensly Didn't Sign, and That's Fine",
        failed = false,
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
            val inset = RING_INSET_DP.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
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
        Text(text = remaining.toString(), style = ClearAirType.hero.copy(fontSize = 52.sp, letterSpacing = (-0.04).em), color = palette.ink)
    }
}

private val RING_SIZE_DP = 132.dp
private val RING_STROKE_DP = 3.dp

/** The ring's circle has a radius of 60 dp in the mock's 132 dp box. */
private val RING_INSET_DP = 6.dp

/**
 * On Its Way, after the send: the steps of the transfer with the state of each, and the words that say the screen can be closed. [summary] says what
 * went to whom. [timing] is how
 * long the network usually takes, for the step that is confirming. [signedAtMs] and [sentAtMs] are the clock times of the first two steps, and the
 * confirming step reads "now" while it waits. [arrived] says the transfer has landed: the mark becomes a check, the last two steps are done, the words about
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
        close = true,
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
        Spacer(Modifier.height(ClearAirSpacing.xs))
        TransferMark(arrived = arrived, modifier = Modifier.align(Alignment.CenterHorizontally))
        SendHeading(text = headline, top = 18.dp, bottom = 0.dp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            text = withMonoAddress(summary),
            style = ClearAirType.small,
            color = palette.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        ClearAirTimeline(
            steps = listOf(
                ClearAirTimelineStep("Signed on Lensly", "Your keys never left it", ClearAirStepState.Done, clockOf(signedAtMs)),
                ClearAirTimelineStep("Sent to $network", "The network accepted it", ClearAirStepState.Done, clockOf(sentAtMs)),
                if (arrived) {
                    ClearAirTimelineStep("Confirming", "Confirmed by the network", ClearAirStepState.Done)
                } else {
                    ClearAirTimelineStep("Confirming", timing, ClearAirStepState.Now, time = "now")
                },
                if (arrived) {
                    ClearAirTimelineStep(withMonoAddress("In $recipient", 12.sp), "Funds reached the recipient", ClearAirStepState.Done)
                } else {
                    ClearAirTimelineStep(withMonoAddress("In $recipient", 12.sp), "Funds reach the recipient", ClearAirStepState.Todo)
                },
            ),
        )
        if (!arrived) {
            Text(
                text = "You can close this screen. Lumio keeps watching and will tell you when it lands.",
                style = ClearAirType.small,
                color = palette.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
}

private val ADDRESS_TAIL = Regex("""\(([^()]+)\)$|(?<= )(?:0x)?[0-9A-Za-z]{4}(?: [0-9A-Za-z]{1,4}){5,}$""")

/**
 * [text] with its address set in the mono face of the mock's recipient line: the part in brackets at the end, as in "Name (0x12 34 ...)", or a bare
 * address in groups of four at the end. The words around it stay as they are. [size] sets the address smaller than the words, as a step title needs, so a long address
 * wraps in two lines of the card.
 */
internal fun withMonoAddress(text: String, size: TextUnit = TextUnit.Unspecified): AnnotatedString {
    val address = ADDRESS_TAIL.find(text)?.let { it.groups[1]?.range ?: it.range } ?: return AnnotatedString(text)
    val mono = SpanStyle(fontFamily = ClearAirType.address.fontFamily, fontWeight = FontWeight.Normal, fontSize = size, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")
    return AnnotatedString(text, listOf(AnnotatedString.Range(mono, address.first, address.last + 1)))
}

/** The disc above the heading: the send arrow with an amber diamond at its corner while the transfer is on its way, and a blue check once it has landed. */
@Composable
private fun TransferMark(arrived: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Box(modifier = modifier.size(TRANSFER_MARK_DP)) {
        Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(palette.raised), contentAlignment = Alignment.Center) {
            ClearAirIcon(
                glyph = if (arrived) ClearAirGlyph.CHECK else ClearAirGlyph.SEND,
                color = if (arrived) palette.vaultBlue else palette.ink,
                size = 30.dp,
            )
        }
        if (!arrived) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp).size(10.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.requiredSize(18.dp).rotate(45f).background(palette.background))
                Box(modifier = Modifier.requiredSize(10.dp).rotate(45f).background(palette.spark))
            }
        }
    }
}

private val TRANSFER_MARK_DP = 76.dp

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** The clock time of [epochMs] in [zone], as the timeline shows it: 24 hours, hours and minutes. */
internal fun clockOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.withZone(zone).format(Instant.ofEpochMilli(epochMs))

/**
 * One outcome page of a send that did not go through: Lensly Didn't Sign, the signature for another send, or a failed broadcast. [failed] draws the
 * mark as a rose outlined alert, and otherwise as a quiet info mark. [body] says where the money is, and [notes] say what happened and what to do.
 * [detail] is the network's own words, when there are any: the first note folds them under Technical Details, closed until the owner opens it.
 * [primary] and [secondary] are the page's actions. The page closes with the X, as the mock's failure page does.
 */
@Composable
fun SendOutcomePage(
    heading: String,
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
    failed: Boolean = true,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = "",
        backLabel = backLabel,
        offline = null,
        close = true,
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
                .padding(top = ClearAirSpacing.xs)
                .align(Alignment.CenterHorizontally)
                .size(TRANSFER_MARK_DP)
                .then(if (failed) Modifier.border(2.dp, palette.rose, CircleShape) else Modifier.background(palette.raised, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            ClearAirIcon(glyph = if (failed) ClearAirGlyph.ALERT else ClearAirGlyph.INFO, color = if (failed) palette.rose else palette.ink, size = 30.dp)
        }
        SendHeading(
            text = heading,
            top = 18.dp,
            bottom = 0.dp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 24.sp,
        )
        Text(
            text = body,
            style = ClearAirType.body,
            color = palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        notes.forEachIndexed { index, (label, text) ->
            OutcomeNote(label = label, text = text, detail = detail.takeIf { index == 0 }, modifier = Modifier.padding(top = if (index == 0) 16.dp else 10.dp))
        }
    }
}

/** A note of the outcome page: a [label] over its [text] on the sunk layer, with the Technical Details of [detail] folded under the text. */
@Composable
private fun OutcomeNote(label: String, text: String, detail: String?, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NOTE_CORNER))
            .background(palette.raised)
            .padding(horizontal = 14.dp, vertical = ClearAirSpacing.sm),
    ) {
        ClearAirEyebrow(text = label, top = 0.dp, bottom = 4.dp)
        Text(text = text, style = ClearAirType.label.copy(fontWeight = FontWeight.Normal), color = palette.ink)
        detail?.let {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ClearAirSpacing.xs)
                    .clickable(role = Role.Button, onClickLabel = if (open) "Hide technical details" else "Show technical details") { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Technical Details", style = ClearAirType.small, color = palette.ink2)
                ClearAirIcon(glyph = ClearAirGlyph.DOWN, color = palette.ink2, size = 16.dp, modifier = Modifier.rotate(if (open) 180f else 0f))
            }
            if (open) Text(text = it, style = ClearAirType.address, color = palette.ink2, modifier = Modifier.padding(top = ClearAirSpacing.xs))
        }
    }
}

private val NOTE_CORNER = 14.dp

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
