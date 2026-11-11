package app.lensly.ui.wallet

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import kotlin.math.roundToInt

/** How many of the newest entries the dot-matrix strip shows. */
private const val RECENT_ENTRIES = 20

/**
 * Two taps on the same button closer together than this are one tap: the second is an accidental double tap and is
 * ignored. Taps this far apart or more each count. Undo is guarded the same way.
 */
const val DOUBLE_TAP_WINDOW_MS = 150L

/** Height of the recent strip and the status line. Each has one height, so the buttons do not move as the entry grows. */
private val LINE_HEIGHT = 32.dp

/** A die face button: at least 88 dp, and tall enough that a wrong face is hard to hit by mistake. */
private val DIE_BUTTON_HEIGHT = 112.dp

/** A coin side button: at least 120 dp. */
private val COIN_BUTTON_HEIGHT = 140.dp

/** The Undo control is deliberately smaller than the value buttons and sits apart from them. */
private val UNDO_HEIGHT = 48.dp

/** Size of the pips drawn on a die face. */
private val DIE_PIP_SIZE = 44.dp

/** Size of the coin drawn on a coin button. */
private val COIN_ICON_SIZE = 64.dp

private val BUTTON_SHAPE = RoundedCornerShape(LenslyRadius.lg)

/** Test tag of the Undo control. */
const val UNDO_TAG = "entry-undo"

/** Test tag of the recent-entries strip, whose text is the newest entries. */
const val RECENT_STRIP_TAG = "entry-recent"

/** Test tag of one value button, e.g. `entry-face-3` or `entry-face-H`. */
fun faceTag(symbol: Char): String = "entry-face-$symbol"

/** Identifies the Undo control in [DoubleTapGuard], which otherwise keys on the symbols. */
private const val UNDO_CONTROL = "undo"

/**
 * Dice or coin entry for the seed ceremony. The owner taps one face at a time. [entered] is the full
 * record so far, and [onSymbol] is called with one more face. Taps are never refused for reaching a
 * count; only [EntropyTargets.MAX_SYMBOLS] stops them. DONE enables at [minimumCount], and
 * [fullStrengthCount] is where the entry reaches its full 256 bits. Undo is off until the first face.
 *
 * A second tap on the same button within [DOUBLE_TAP_WINDOW_MS] is ignored. [nowMs] is the clock those taps are
 * timed by, in milliseconds.
 */
@Composable
fun DiceEntryScreen(
    mode: EntropyMode,
    entered: String,
    minimumCount: Int,
    fullStrengthCount: Int,
    onSymbol: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = { SystemClock.uptimeMillis() },
) {
    val progress = entropyProgress(mode, entered.length, minimumCount, fullStrengthCount)
    val complete = entered.length >= minimumCount
    val atCap = entered.length >= EntropyTargets.MAX_SYMBOLS
    val unit = if (mode == EntropyMode.Dice) "rolls" else "flips"
    val guard = remember(mode) { DoubleTapGuard<Any>() }
    LenslyScreen(
        title = if (mode == EntropyMode.Dice) "Roll the dice" else "Flip the coins",
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                UndoControl(
                    enabled = entered.isNotEmpty(),
                    onClick = { if (guard.accept(UNDO_CONTROL, nowMs())) onBackspace() },
                )
                PillButton(
                    text = "Done",
                    onClick = onDone,
                    accent = true,
                    enabled = complete,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Stat(label = unit, value = "${entered.length}")
            Stat(label = "Bits of $FULL_STRENGTH_BITS", value = "${progress.bits.roundToInt()}")
            Stat(label = "Last", value = entered.lastOrNull()?.toString() ?: "-")
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Recent")
        Spacer(Modifier.height(LenslySpacing.xs))
        Box(
            modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (entered.isEmpty()) {
                Text(
                    text = "Tap a face to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DotText(
                    text = recentStrip(entered),
                    modifier = Modifier.testTag(RECENT_STRIP_TAG),
                    fontSize = 20.sp,
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        ProgressTrack(fraction = progress.fraction)
        Spacer(Modifier.height(LenslySpacing.sm))
        EntryStatus(status = progress.status, minimumCount = minimumCount, unit = unit)

        Spacer(Modifier.weight(1f))

        EntryPad(
            mode = mode,
            onSymbol = { symbol ->
                if (!atCap && mode.accepts(symbol) && guard.accept(symbol, nowMs())) onSymbol(symbol)
            },
        )
        Spacer(Modifier.height(LenslySpacing.md))
    }
}

/** One figure in the header: a muted label over a dot-matrix number. */
@Composable
private fun Stat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs)) {
        WalletEyebrow(label)
        DotText(text = value, fontSize = 40.sp)
    }
}

/** What the entry needs next: how many more symbols reach DONE, or that it is full strength. */
@Composable
private fun EntryStatus(status: EntropyStatus, minimumCount: Int, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        when (status) {
            EntropyStatus.BelowMinimum -> WalletEyebrow("At least $minimumCount $unit")
            EntropyStatus.MinimumReached ->
                WalletEyebrow("Minimum reached · keep going for $FULL_STRENGTH_BITS bits")
            EntropyStatus.FullStrength -> {
                FullStrengthChip()
                WalletEyebrow("You may continue or tap done")
            }
        }
    }
}

/** The newest [RECENT_ENTRIES] entries, with a leading ellipsis when older entries are hidden. */
private fun recentStrip(entered: String): String {
    val tail = entered.takeLast(RECENT_ENTRIES)
    return if (entered.length > RECENT_ENTRIES) "…$tail" else tail
}

/** Outlined chip shown once the entry reaches full strength. */
@Composable
private fun FullStrengthChip() {
    Box(
        modifier = Modifier
            .border(1.dp, LenslyOnSurface, RoundedCornerShape(percent = 50))
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.xxs + 2.dp),
    ) {
        Text(
            text = "FULL $FULL_STRENGTH_BITS-BIT",
            style = MaterialTheme.typography.labelSmall,
            color = LenslyOnSurface,
        )
    }
}

/** The value buttons for [mode]: the dice faces or the two coin sides. */
@Composable
private fun EntryPad(mode: EntropyMode, onSymbol: (Char) -> Unit) {
    when (mode) {
        EntropyMode.Dice -> DicePad(onSymbol)
        EntropyMode.Coins -> CoinPad(onSymbol)
    }
}

/** The six dice faces in a 3 by 2 grid that fills the width, with a 12 dp gap. */
@Composable
private fun DicePad(onSymbol: (Char) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        for (row in listOf("123", "456")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                for (symbol in row) {
                    EntryButton(
                        tag = faceTag(symbol),
                        description = "Roll ${symbol - '0'}",
                        modifier = Modifier.weight(1f).height(DIE_BUTTON_HEIGHT),
                        onClick = { onSymbol(symbol) },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
                        ) {
                            DieFace(face = symbol - '0', faceSize = DIE_PIP_SIZE)
                            Text(
                                text = symbol.toString(),
                                style = MaterialTheme.typography.headlineLarge,
                                color = LenslyOnSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Heads and Tails side by side, with a 16 dp gap. Each is tall and wide, so either is easy to tap. */
@Composable
private fun CoinPad(onSymbol: (Char) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.md),
    ) {
        CoinButton(
            side = 'H',
            label = "Heads",
            modifier = Modifier.weight(1f).height(COIN_BUTTON_HEIGHT),
            onClick = { onSymbol('H') },
        )
        CoinButton(
            side = 'T',
            label = "Tails",
            modifier = Modifier.weight(1f).height(COIN_BUTTON_HEIGHT),
            onClick = { onSymbol('T') },
        )
    }
}

@Composable
private fun CoinButton(side: Char, label: String, modifier: Modifier, onClick: () -> Unit) {
    EntryButton(
        tag = faceTag(side),
        description = label,
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .size(COIN_ICON_SIZE)
                    .border(2.dp, LenslyOnSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = side.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LenslyOnSurface,
                )
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = LenslyOnSurface,
            )
        }
    }
}

/**
 * A value button: a large surface tile that shrinks and dims while pressed and plays the key haptic on
 * every tap. [description] is what a screen reader says.
 */
@Composable
private fun EntryButton(
    tag: String,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    Box(
        modifier = modifier
            .testTag(tag)
            .pressFeedback(interaction, enabled = true)
            .clip(BUTTON_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant, BUTTON_SHAPE)
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Small outlined pill that removes the last entry. Kept apart from the value buttons, in the bottom bar. */
@Composable
private fun UndoControl(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    Box(
        modifier = Modifier
            .testTag(UNDO_TAG)
            .height(UNDO_HEIGHT)
            .pressFeedback(interaction, enabled)
            .clip(LenslyPillShape)
            .border(1.dp, LenslyOutlineInteractive, LenslyPillShape)
            .tapHaptic(haptics, enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = LenslySpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "UNDO",
            style = MaterialTheme.typography.labelLarge,
            color = LenslyOnSurface,
        )
    }
}

/** Pip centres for each face, as fractions of the die's width and height. */
private const val PIP_NEAR = 0.3f
private const val PIP_MID = 0.5f
private const val PIP_FAR = 0.7f

private val DIE_PIPS: Map<Int, List<Offset>> = mapOf(
    1 to listOf(Offset(PIP_MID, PIP_MID)),
    2 to listOf(Offset(PIP_NEAR, PIP_NEAR), Offset(PIP_FAR, PIP_FAR)),
    3 to listOf(Offset(PIP_NEAR, PIP_NEAR), Offset(PIP_MID, PIP_MID), Offset(PIP_FAR, PIP_FAR)),
    4 to listOf(
        Offset(PIP_NEAR, PIP_NEAR), Offset(PIP_FAR, PIP_NEAR),
        Offset(PIP_NEAR, PIP_FAR), Offset(PIP_FAR, PIP_FAR),
    ),
    5 to listOf(
        Offset(PIP_NEAR, PIP_NEAR), Offset(PIP_FAR, PIP_NEAR), Offset(PIP_MID, PIP_MID),
        Offset(PIP_NEAR, PIP_FAR), Offset(PIP_FAR, PIP_FAR),
    ),
    6 to listOf(
        Offset(PIP_NEAR, PIP_NEAR), Offset(PIP_FAR, PIP_NEAR),
        Offset(PIP_NEAR, PIP_MID), Offset(PIP_FAR, PIP_MID),
        Offset(PIP_NEAR, PIP_FAR), Offset(PIP_FAR, PIP_FAR),
    ),
)

/** A die face drawn as a rounded square outline with its pips, in the light on-surface colour. */
@Composable
private fun DieFace(face: Int, faceSize: Dp, color: Color = LenslyOnSurface) {
    Canvas(Modifier.size(faceSize)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.06f
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(w - stroke, h - stroke),
            cornerRadius = CornerRadius(w * 0.18f),
            style = Stroke(width = stroke),
        )
        val pipRadius = w * 0.075f
        for (pip in DIE_PIPS.getValue(face)) {
            drawCircle(color = color, radius = pipRadius, center = Offset(pip.x * w, pip.y * h))
        }
    }
}

/**
 * Ignores a second tap on the same button that lands within [windowMs] of the last accepted tap on it. Only accepted
 * taps start the window, so a bounce cannot keep a button locked out. Each button is tracked on its own, so taps on
 * different faces never block one another.
 */
internal class DoubleTapGuard<K : Any>(private val windowMs: Long = DOUBLE_TAP_WINDOW_MS) {
    private val lastAccepted = HashMap<K, Long>()

    /** True when a tap on [button] at [nowMs] should count, and records it. */
    fun accept(button: K, nowMs: Long): Boolean {
        val last = lastAccepted[button]
        if (last != null && nowMs - last < windowMs) return false
        lastAccepted[button] = nowMs
        return true
    }
}
