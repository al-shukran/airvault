package app.lensly.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.lensly.ui.theme.LocalMonoFamily
import app.lensly.ui.theme.labelCase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class KeyboardPage { Letters, Symbols, MoreSymbols }

private val KEY_GAP = 4.dp
private val ROW_GAP = 8.dp
private val KEY_HEIGHT = 54.dp
private val ROW_HEIGHT = KEY_HEIGHT + ROW_GAP
private val KEY_CORNER = 10.dp
private val KEY_SHAPE = RoundedCornerShape(KEY_CORNER)
private val GLYPH_SIZE = 18.dp
private val LETTER_TEXT_SIZE = 22.sp
private const val DISABLED_ALPHA = 0.4f
private const val HELD_DONE_ALPHA = 0.35f
private const val PRESSED_DARKEN = 0.25f

/** A held key repeats once it has been down this long, and then once per interval while it stays down. */
private const val KEY_REPEAT_START_MS = 400L
private const val KEY_REPEAT_INTERVAL_MS = 50L

private const val NUMBER_ROW = "1234567890"
private const val LETTERS_TOP = "qwertyuiop"
private const val LETTERS_MIDDLE = "asdfghjkl"
private const val LETTERS_BOTTOM = "zxcvbnm"

// The symbol pages follow AOSP LatinIME's rows: 10 keys, then 9 inset by half a key, then 7 beside the switch key. The more-symbols
// rows are AOSP's second page (its first row is verified; the currency keys are AOSP's four currency styles).
private const val SYMBOLS_TOP = "@#$%&*()-+"
private const val SYMBOLS_MIDDLE = "_/\\<>=~^|"
private const val SYMBOLS_BOTTOM = "!?'\":;["
private const val MORE_SYMBOLS_TOP = "~`|•√π÷×¶∆"
private const val MORE_SYMBOLS_MIDDLE = "€£$¥^°={}"
private const val MORE_SYMBOLS_BOTTOM = "\\©®™℅[]"

/** Test tag of the key preview. It carries no text, so the typed character stays out of accessibility. */
internal const val KEY_PREVIEW_TAG = "keyboard-preview"

private val PREVIEW_WIDTH = 44.dp
private val PREVIEW_HEIGHT = 56.dp
private val PREVIEW_GAP = 6.dp
private val PREVIEW_SHAPE = RoundedCornerShape(12.dp)
private val PREVIEW_TEXT_SIZE = 32.sp

/**
 * In-app keyboard for secret entry. Keys are handled here, so the password never reaches the
 * system IME (no suggestions, no IME logging, no clipboard). Every key gives the same haptic tap on
 * press (see [rememberKeyHaptics]) and no sound, so the buzz never differs by key. A pressed
 * character key shows its character, enlarged, above the key (see [KeyPreview]). Shift, backspace,
 * space, and done show no preview. A pressed key otherwise only darkens its own background.
 *
 * Keys are rounded rectangles that share the full width of the screen. Every row is ten slots wide
 * (see [KeyRow]), so the keys stretch to the available width at any phone size, and the gaps between
 * keys and rows belong to the keys beside them. The number row sits on top of every
 * page, so the keyboard keeps one height when the page changes. The second row of letters and of
 * each symbol page is inset by half a key on each side, and the bottom row of letters has shift and
 * backspace at 1.5 key widths. The bottom row is the down-arrow key that hides the keyboard, `123` (or
 * `ABC`), `,`, SPACE, `.` and done.
 *
 * Holding a key that has alternates opens them above it (see [alternatesGesture]). There are three
 * pages: letters, the symbols page (`=\<` switches to the more symbols), and the more-symbols page
 * (`?123` switches back), as in AOSP LatinIME.
 *
 * Shift has three states (see [Shift]). A tap makes only the next letter uppercase, and the number row
 * shows the symbol for that key. Then shift turns itself off. Two taps within [DOUBLE_TAP_MS] lock caps until shift is tapped again. The
 * `123` key switches to the symbols page, which clears shift, and `ABC` returns to letters. Once a password is held by
 * the caller, the caller decides what [onDone] means (next step, submit, or unlock).
 *
 * [doneHeld] holds Done, dimmed and not tappable, while the other keys still type: the gate uses it while a failure delay runs.
 */
@Composable
fun SecureKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    doneHeld: Boolean = false,
) {
    var page by rememberSaveable { mutableStateOf(KeyboardPage.Letters) }
    val shift = rememberSaveable(saver = ShiftSaver) { Shift() }
    val picker = remember { AlternatesPicker() }
    val lettersPage = page == KeyboardPage.Letters

    // The keyboard is a box so the open alternates can lie over the rows above a key. Every character key goes through
    // ShiftedKey, so a letter's label and its tap read shift when they fire, and a Shift and a letter in one frame still agree.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .keyboardSurface()
            .navigationBarsPadding()
            .onGloballyPositioned { picker.attach(it) },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            KeyRow {
                for (digit in NUMBER_ROW) ShiftedKey(digit, enabled, shift, picker, onKey)
            }
            when (page) {
                KeyboardPage.Letters -> {
                    KeyRow {
                        for (base in LETTERS_TOP) ShiftedKey(base, enabled, shift, picker, onKey)
                    }
                    KeyRow {
                        InsetKeys(LETTERS_MIDDLE, inset = 0.5f) { base, lead, trail ->
                            ShiftedKey(base, enabled, shift, picker, onKey, lead = lead, trail = trail)
                        }
                    }
                    KeyRow {
                        ShiftKey(slots = 1.5f, mode = shift.mode, enabled = enabled) { shift.tapShift() }
                        for (base in LETTERS_BOTTOM) ShiftedKey(base, enabled, shift, picker, onKey)
                        BackspaceKey(slots = 1.5f, enabled = enabled, onClick = onBackspace)
                    }
                }
                KeyboardPage.Symbols -> SymbolRows(
                    top = SYMBOLS_TOP,
                    middle = SYMBOLS_MIDDLE,
                    bottom = SYMBOLS_BOTTOM,
                    switchLabel = "=\\<",
                    switchDescription = "More symbols",
                    enabled = enabled,
                    shift = shift,
                    picker = picker,
                    onKey = onKey,
                    onBackspace = onBackspace,
                    onSwitch = { page = KeyboardPage.MoreSymbols },
                )
                KeyboardPage.MoreSymbols -> SymbolRows(
                    top = MORE_SYMBOLS_TOP,
                    middle = MORE_SYMBOLS_MIDDLE,
                    bottom = MORE_SYMBOLS_BOTTOM,
                    switchLabel = "?123",
                    switchDescription = "Symbols",
                    enabled = enabled,
                    shift = shift,
                    picker = picker,
                    onKey = onKey,
                    onBackspace = onBackspace,
                    onSwitch = { page = KeyboardPage.Symbols },
                )
            }

            KeyRow {
                DismissKey(enabled = enabled)
                SwitchKey(
                    label = if (lettersPage) "123" else "ABC",
                    description = if (lettersPage) "Symbols" else "Letters",
                    slots = 1.5f,
                    enabled = enabled,
                ) {
                    if (lettersPage) shift.reset()
                    page = if (lettersPage) KeyboardPage.Symbols else KeyboardPage.Letters
                }
                ShiftedKey(',', enabled, shift, picker, onKey)
                Key(
                    description = "Space",
                    background = MaterialTheme.colorScheme.surfaceContainerHigh,
                    enabled = enabled,
                    onClick = { onKey(shift.type(' ')) },
                    slots = 4f,
                ) {
                    Text(
                        text = labelCase("Space"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                ShiftedKey('.', enabled, shift, picker, onKey)
                DoneKey(slots = 1.5f, enabled = enabled, held = doneHeld, onClick = onDone)
            }
        }
        AlternatesStrip(picker)
    }
}

/**
 * The rows of a symbols page: [top] spans the width, [middle] is inset by half a key, and the bottom row holds the switch key
 * ([onSwitch]), [bottom], and backspace.
 */
@Composable
private fun SymbolRows(
    top: String,
    middle: String,
    bottom: String,
    switchLabel: String,
    switchDescription: String,
    enabled: Boolean,
    shift: Shift,
    picker: AlternatesPicker,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSwitch: () -> Unit,
) {
    KeyRow {
        for (c in top) ShiftedKey(c, enabled, shift, picker, onKey)
    }
    KeyRow {
        InsetKeys(middle, inset = 0.5f) { c, lead, trail ->
            ShiftedKey(c, enabled, shift, picker, onKey, lead = lead, trail = trail)
        }
    }
    KeyRow {
        SwitchKey(label = switchLabel, description = switchDescription, slots = 1.5f, enabled = enabled, onClick = onSwitch)
        for (c in bottom) ShiftedKey(c, enabled, shift, picker, onKey)
        BackspaceKey(slots = 1.5f, enabled = enabled, onClick = onBackspace)
    }
}

/**
 * A key that types [base] as shift makes it: a letter takes shift's case, a digit shows its symbol while a one-shot shift is armed,
 * and other keys are typed as they are. Its alternates, if any, take the same case.
 */
@Composable
private fun RowScope.ShiftedKey(
    base: Char,
    enabled: Boolean,
    shift: Shift,
    picker: AlternatesPicker,
    onKey: (Char) -> Unit,
    lead: Float = 0f,
    trail: Float = 0f,
) {
    CharKey(
        label = shift.labelFor(base),
        enabled = enabled,
        onTap = { onKey(shift.type(base)) },
        lead = lead,
        trail = trail,
        alternates = ALTERNATES[base]?.map(shift::labelFor)?.joinToString("").orEmpty(),
        picker = picker,
        onChoose = { onKey(shift.type(it)) },
    )
}

/** A key that switches the page: the bottom row's `123` or `ABC`, and the switch on each symbols page. */
@Composable
private fun RowScope.SwitchKey(
    label: String,
    description: String,
    slots: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = description,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        enabled = enabled,
        onClick = onClick,
        slots = slots,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The digit row of [SecureHexKeyboard]. With [HEX_LETTER_ROW] it holds every hex digit. */
internal const val HEX_DIGIT_ROW = "1234567890"

/** The letter row of [SecureHexKeyboard]: the letters a to f, and x, which is the second character of a 0x prefix. */
internal const val HEX_LETTER_ROW = "abcdefx"

/**
 * In-app keyboard for hex secrets, such as a private key. It holds the digits, the letters a to f, and x for a leading
 * 0x, and it has no page to switch to, so a 64-digit key is typed on one screen. As in [SecureKeyboard], the keys are
 * handled here, so no system IME opens. The caller decides what [onDone] means.
 */
@Composable
fun SecureHexKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .keyboardSurface()
            .navigationBarsPadding(),
    ) {
        KeyRow {
            for (c in HEX_DIGIT_ROW) CharKey(c, enabled, onTap = { onKey(c) })
        }
        // Seven keys sit centred under the ten above them, with a space of 1.5 keys on each side.
        KeyRow {
            InsetKeys(HEX_LETTER_ROW, inset = 1.5f) { c, lead, trail ->
                CharKey(c, enabled, onTap = { onKey(c) }, lead = lead, trail = trail)
            }
        }
        // Dismiss and done each take one key's space beside them, so the row is ten keys wide, as the rows above are.
        KeyRow {
            DismissKey(slots = 2f, trail = 1f, enabled = enabled)
            BackspaceKey(slots = 3f, enabled = enabled, onClick = onBackspace)
            DoneKey(slots = 3f, trail = 1f, enabled = enabled, onClick = onDone)
        }
    }
}

/**
 * Makes the whole keyboard a tap target. The keys cover the keyboard's area, so a tap that lands on no key, such as the bar that keeps
 * clear of the navigation bar, is consumed here and does not reach the screen behind and hide the keyboard. Taps outside the keyboard
 * still reach the screen. There is no visual feedback and no semantics, so the keys look and act as before.
 */
private fun Modifier.keyboardSurface(): Modifier = pointerInput(Unit) { detectTapGestures {} }

/**
 * A row ten slots wide, where a slot is one key's width plus the gap after it. A key takes its slots (see [Key]), so every slot is
 * the same width on every row, and the row's keys tile its width with no space left over. The row is [ROW_HEIGHT] tall: a key and
 * the half gap above and below it, so the gaps between rows belong to the keys beside them.
 */
@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT),
        content = content,
    )
}

/**
 * Nine or seven keys centred in a ten-slot row, with [inset] slots left empty on each side. The first key takes the left inset and the
 * last takes the right one, so the space beside the row is a key's touch area, as in the rest of the keyboard.
 */
@Composable
private fun RowScope.InsetKeys(
    chars: String,
    inset: Float,
    key: @Composable RowScope.(char: Char, lead: Float, trail: Float) -> Unit,
) {
    chars.forEachIndexed { index, char ->
        key(char, if (index == 0) inset else 0f, if (index == chars.lastIndex) inset else 0f)
    }
}

/**
 * Base key. Its touch area is [slots] row slots, plus [lead] slots before it and [trail] slots after it (see [InsetKeys]). Its face is
 * a rounded rectangle of [KEY_HEIGHT], inset by half of [KEY_GAP] on each side and half of [ROW_GAP] above and below, so the gaps
 * belong to the keys beside it. While pressed, the face darkens. A key with [preview] also
 * shows that text above its face while pressed. [stateText] is announced as the key's state. A [repeating] key fires on press, and again
 * while it is held.
 * A key with [alternates] (and its [picker]) opens them on a long press, and [onChoose] takes the alternate released on (see [alternatesGesture]).
 */
@Composable
private fun RowScope.Key(
    description: String,
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    slots: Float = 1f,
    lead: Float = 0f,
    trail: Float = 0f,
    stateText: String? = null,
    preview: String? = null,
    repeating: Boolean = false,
    alternates: String = "",
    picker: AlternatesPicker? = null,
    onChoose: (Char) -> Unit = {},
    disabledAlpha: Float = DISABLED_ALPHA,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = rememberKeyHaptics()
    val place = remember { KeyPlace() }
    val cell = with(LocalDensity.current) { ALTERNATE_CELL.toPx() }
    val fill = if (pressed && enabled) lerp(background, Color.Black, PRESSED_DARKEN) else background
    val withAlternates = picker != null && alternates.isNotEmpty()
    val press = when {
        repeating -> Modifier.repeatWhileHeld(interactionSource, enabled, onClick)
        picker != null && alternates.isNotEmpty() -> Modifier.alternatesGesture(
            interactionSource = interactionSource,
            enabled = enabled,
            place = place,
            picker = picker,
            alternates = alternates,
            cell = cell,
            onTap = onClick,
            onChoose = onChoose,
        )
        else -> Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }

    Row(
        modifier = Modifier
            .weight(lead + slots + trail)
            .fillMaxHeight()
            .onGloballyPositioned { place.cell = it }
            .tapHaptic(haptics, enabled)
            .then(press)
            .semantics {
                contentDescription = description
                if (stateText != null) stateDescription = stateText
                // A key whose press is a gesture still announces a click, and a disabled key announces none.
                if (repeating || withAlternates) {
                    role = Role.Button
                    if (enabled) {
                        onClick(label = description, action = {
                            onClick()
                            true
                        })
                    }
                }
            },
    ) {
        if (lead > 0f) Spacer(Modifier.weight(lead))
        Box(
            modifier = Modifier
                .weight(slots)
                .fillMaxHeight()
                .padding(horizontal = KEY_GAP / 2, vertical = ROW_GAP / 2)
                .alpha(if (enabled) 1f else disabledAlpha)
                .clip(KEY_SHAPE)
                .background(fill, KEY_SHAPE)
                .onGloballyPositioned { place.face = it },
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (preview != null && pressed) KeyPreview(preview)
        }
        if (trail > 0f) Spacer(Modifier.weight(trail))
    }
}

/**
 * Fires [fire] when the pointer goes down, then again every [KEY_REPEAT_INTERVAL_MS] once it has been held for [KEY_REPEAT_START_MS].
 * The repeats stop when the pointer lifts or leaves the key. The press shows on [interactionSource], as a click does.
 */
private fun Modifier.repeatWhileHeld(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    fire: () -> Unit,
): Modifier = pointerInput(enabled, fire) {
    if (!enabled) return@pointerInput
    detectTapGestures(
        onPress = { offset ->
            val press = PressInteraction.Press(offset)
            interactionSource.emit(press)
            fire()
            coroutineScope {
                val repeats = launch {
                    delay(KEY_REPEAT_START_MS)
                    while (true) {
                        fire()
                        delay(KEY_REPEAT_INTERVAL_MS)
                    }
                }
                val released = tryAwaitRelease()
                repeats.cancel()
                interactionSource.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        },
    )
}

@Composable
private fun RowScope.CharKey(
    label: Char,
    enabled: Boolean,
    onTap: () -> Unit,
    lead: Float = 0f,
    trail: Float = 0f,
    alternates: String = "",
    picker: AlternatesPicker? = null,
    onChoose: (Char) -> Unit = {},
) {
    // The preview gives way to the strip while alternates are open, so only the strip shows the choice.
    val previewed by remember(picker) { derivedStateOf { picker?.open == null } }
    Key(
        description = label.toString(),
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        enabled = enabled,
        onClick = onTap,
        lead = lead,
        trail = trail,
        preview = if (previewed) label.toString() else null,
        alternates = alternates,
        picker = picker,
        onChoose = onChoose,
    ) {
        Text(
            text = label.toString(),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalMonoFamily.current, fontSize = LETTER_TEXT_SIZE),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The enlarged character above a pressed key. It is a popup window, so it is drawn over the key row and not clipped by the key. Its
 * secure policy is the default, which inherits FLAG_SECURE from the keyboard's window, so a screenshot or a screen share never
 * captures it on a phone. Its only semantics is a test tag, since the key already announces itself.
 */
@Composable
private fun KeyPreview(text: String) {
    val lift = with(LocalDensity.current) { (PREVIEW_HEIGHT + PREVIEW_GAP).roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -lift),
        properties = PopupProperties(focusable = false),
    ) {
        Box(
            modifier = Modifier
                .size(width = PREVIEW_WIDTH, height = PREVIEW_HEIGHT)
                .clearAndSetSemantics { testTag = KEY_PREVIEW_TAG }
                .background(MaterialTheme.colorScheme.onSurface, PREVIEW_SHAPE),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalMonoFamily.current, fontSize = PREVIEW_TEXT_SIZE),
                color = MaterialTheme.colorScheme.background,
            )
        }
    }
}

@Composable
private fun RowScope.ShiftKey(
    slots: Float,
    mode: ShiftMode,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val on = mode != ShiftMode.Off
    Key(
        description = "Shift",
        background = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        enabled = enabled,
        onClick = onClick,
        slots = slots,
        stateText = when (mode) {
            ShiftMode.Off -> null
            ShiftMode.OneShot -> "Shift on"
            ShiftMode.CapsLock -> "Caps lock on"
        },
    ) {
        ArrowGlyph(color = if (on) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface, filled = on, capsLock = mode == ShiftMode.CapsLock)
    }
}

@Composable
private fun RowScope.BackspaceKey(
    slots: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = "Backspace",
        background = MaterialTheme.colorScheme.primary,
        enabled = enabled,
        onClick = onClick,
        slots = slots,
        repeating = true,
    ) {
        BackspaceGlyph(color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun RowScope.DoneKey(
    slots: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    trail: Float = 0f,
    held: Boolean = false,
) {
    Key(
        description = "Done",
        background = MaterialTheme.colorScheme.onSurface,
        enabled = enabled && !held,
        onClick = onClick,
        slots = slots,
        trail = trail,
        disabledAlpha = if (held) HELD_DONE_ALPHA else DISABLED_ALPHA,
    ) {
        EnterGlyph(color = MaterialTheme.colorScheme.background)
    }
}

/** Hides the keyboard by calling [LocalKeyboardDismiss]. It sits at the left of the bottom row, next to the page key. */
@Composable
private fun RowScope.DismissKey(
    enabled: Boolean,
    slots: Float = 1f,
    trail: Float = 0f,
) {
    val dismiss = LocalKeyboardDismiss.current
    Key(
        description = "Hide keyboard",
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        enabled = enabled,
        onClick = dismiss,
        slots = slots,
        trail = trail,
    ) {
        ChevronDownGlyph(color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The down arrow of [DismissKey]: a chevron that points at the bottom edge, where the keyboard slides away. */
@Composable
private fun ChevronDownGlyph(color: Color) {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val w = size.width
        val h = size.height
        val chevron = Path().apply {
            moveTo(w * 0.14f, h * 0.36f)
            lineTo(w * 0.5f, h * 0.72f)
            lineTo(w * 0.86f, h * 0.36f)
        }
        drawPath(
            chevron,
            color,
            style = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** The shift arrow. With [capsLock] a bar sits under it, and the arrow is drawn shorter to make room. */
@Composable
private fun ArrowGlyph(color: Color, filled: Boolean, capsLock: Boolean) {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val w = size.width
        val h = size.height
        val squash = if (capsLock) 0.7f else 1f
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f * squash)
            lineTo(w * 0.12f, h * 0.5f * squash)
            lineTo(w * 0.34f, h * 0.5f * squash)
            lineTo(w * 0.34f, h * 0.88f * squash)
            lineTo(w * 0.66f, h * 0.88f * squash)
            lineTo(w * 0.66f, h * 0.5f * squash)
            lineTo(w * 0.88f, h * 0.5f * squash)
            close()
        }
        val style = if (filled) Fill else Stroke(
            width = w * 0.1f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawPath(path, color, style = style)
        if (capsLock) {
            drawLine(
                color,
                Offset(w * 0.2f, h * 0.9f),
                Offset(w * 0.8f, h * 0.9f),
                strokeWidth = w * 0.1f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun BackspaceGlyph(color: Color) {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        val outline = Path().apply {
            moveTo(w * 0.38f, h * 0.18f)
            lineTo(w * 0.92f, h * 0.18f)
            lineTo(w * 0.92f, h * 0.82f)
            lineTo(w * 0.38f, h * 0.82f)
            lineTo(w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(
            outline,
            color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(
            color,
            Offset(w * 0.55f, h * 0.36f),
            Offset(w * 0.79f, h * 0.64f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(w * 0.79f, h * 0.36f),
            Offset(w * 0.55f, h * 0.64f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun EnterGlyph(color: Color) {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val w = size.width
        val h = size.height
        val style = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val hook = Path().apply {
            moveTo(w * 0.86f, h * 0.18f)
            lineTo(w * 0.86f, h * 0.56f)
            lineTo(w * 0.18f, h * 0.56f)
        }
        val head = Path().apply {
            moveTo(w * 0.42f, h * 0.32f)
            lineTo(w * 0.18f, h * 0.56f)
            lineTo(w * 0.42f, h * 0.8f)
        }
        drawPath(hook, color, style = style)
        drawPath(head, color, style = style)
    }
}
