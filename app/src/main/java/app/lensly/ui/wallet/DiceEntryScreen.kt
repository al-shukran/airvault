package app.lensly.ui.wallet

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import kotlin.math.roundToInt

/** How many of the newest entries the dot-matrix strip shows. */
private const val RECENT_ENTRIES = 20

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

/** Test tag of the last-entries row above the value buttons. */
const val LAST_ENTRIES_TAG = "entry-last"

/** How many of the newest entries the last-entries row shows. The newest always sits in the rightmost slot. */
private const val LAST_SLOTS = 5

/** Height of the last-entries row. */
private val LAST_ROW_HEIGHT = 72.dp

/** Size of a die face or coin in a last-entries slot. */
private val LAST_FACE_SIZE = 44.dp

/**
 * Dice or coin entry for the seed ceremony. The owner taps one face at a time. [entered] is the full
 * record so far, and [onSymbol] is called with one more face. Taps are never refused for reaching a
 * count; only [EntropyTargets.MAX_SYMBOLS] stops them. DONE enables at [minimumCount], and
 * [fullStrengthCount] is where the entry reaches its full 256 bits. Undo is off until the first face.
 * Every tap on a value button records one entry, and every Undo tap removes one.
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
) {
    val progress = entropyProgress(mode, entered.length, minimumCount, fullStrengthCount)
    val complete = entered.length >= minimumCount
    val atCap = entered.length >= EntropyTargets.MAX_SYMBOLS
    val unit = if (mode == EntropyMode.Dice) "rolls" else "flips"
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
                    onClick = onBackspace,
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
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xl)) {
            Stat(label = unit, value = "${entered.length}")
            Stat(label = "Bits of $FULL_STRENGTH_BITS", value = "${progress.bits.roundToInt()}")
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

        LastEntriesRow(mode = mode, entered = entered)
        Spacer(Modifier.height(LenslySpacing.sm))
        EntryPad(
            mode = mode,
            onSymbol = { symbol -> if (!atCap && mode.accepts(symbol)) onSymbol(symbol) },
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

/**
 * The last [LAST_SLOTS] entries as faces, right-aligned so the newest is always in the rightmost slot, which has an
 * accent border. Empty slots are outlined placeholders. The row is spoken as the entries, oldest first.
 */
@Composable
private fun LastEntriesRow(mode: EntropyMode, entered: String) {
    val slots: List<Char?> = List(LAST_SLOTS) { index -> entered.getOrNull(entered.length - LAST_SLOTS + index) }
    val spoken = entered.takeLast(LAST_SLOTS).toList().joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LAST_ROW_HEIGHT)
            .testTag(LAST_ENTRIES_TAG)
            .semantics {
                contentDescription = if (spoken.isEmpty()) "No entries yet" else "Last entries: $spoken"
            },
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        slots.forEachIndexed { index, symbol ->
            LastSlot(
                mode = mode,
                symbol = symbol,
                newest = index == LAST_SLOTS - 1 && symbol != null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** One slot of the last-entries row: the face when filled, an outline when empty, accent when newest. */
@Composable
private fun LastSlot(mode: EntropyMode, symbol: Char?, newest: Boolean, modifier: Modifier) {
    val border = if (newest) {
        Modifier.border(2.dp, LenslyAccent, BUTTON_SHAPE)
    } else {
        Modifier.border(1.dp, LenslyOutline, BUTTON_SHAPE)
    }
    Box(
        modifier = modifier
            .clip(BUTTON_SHAPE)
            .background(
                if (symbol != null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                BUTTON_SHAPE,
            )
            .then(border),
        contentAlignment = Alignment.Center,
    ) {
        when {
            symbol == null -> Unit
            mode == EntropyMode.Dice -> DieFace(face = symbol - '0', faceSize = LAST_FACE_SIZE)
            else -> CoinMark(side = symbol, markSize = LAST_FACE_SIZE)
        }
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
            CoinMark(side = side, markSize = COIN_ICON_SIZE)
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = LenslyOnSurface,
            )
        }
    }
}

/** A coin drawn as a ring with its side letter inside. */
@Composable
private fun CoinMark(side: Char, markSize: Dp) {
    Box(
        modifier = Modifier
            .size(markSize)
            .border(2.dp, LenslyOnSurface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = side.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = LenslyOnSurface,
        )
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
