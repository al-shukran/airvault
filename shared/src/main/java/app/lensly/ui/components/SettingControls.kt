package app.lensly.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.theme.LenslySpacing
import kotlin.math.roundToInt

/** The opacity of a control that is switched off. */
private const val DISABLED_CONTROL_ALPHA = 0.4f

/** The most digits a number field takes, so a typed number always fits an Int. */
private const val MAX_NUMBER_DIGITS = 9

/**
 * One choice of a list: its [title], one line of [description] under it, and a radio on the right. The whole row is the choice. A tap
 * calls [onSelect], and the caller applies the choice. [selected] fills the radio.
 */
@Composable
fun RadioRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    SettingsItem {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .pressFeedback(interaction, enabled)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onSelect,
                )
                .tapHaptic(haptics, enabled)
                .then(if (enabled) Modifier else Modifier.alpha(DISABLED_CONTROL_ALPHA))
                .padding(vertical = LenslySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(LenslySpacing.xxs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(LenslySpacing.sm))
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

/**
 * A value picked from a short ordered list. The chosen value shows big above a slider with one stop to each value, and [caption]
 * says what that value is under it, such as "Default". The caption line is always there, so the layout does not move when it
 * changes. [selected] is null while the saved value is read, and the slider is then off and shows a dash. A drag or a step applies
 * its value when it ends, so the caller saves once per gesture. [options] needs at least two values.
 */
@Composable
fun <T> SliderSetting(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    caption: (T) -> String? = { null },
) {
    require(options.size >= 2) { "A slider needs at least two values." }
    val selectedIndex = selected?.let { options.indexOf(it) }?.takeIf { it >= 0 }
    var position by remember(selectedIndex) { mutableFloatStateOf((selectedIndex ?: 0).toFloat()) }
    val shown = options[position.roundToInt().coerceIn(0, options.lastIndex)]
    Column(modifier = modifier.fillMaxWidth().padding(vertical = LenslySpacing.md)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = if (selectedIndex == null) "—" else label(shown),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = (if (selectedIndex == null) null else caption(shown)) ?: " ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onSelect(options[position.roundToInt().coerceIn(0, options.lastIndex)]) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            enabled = selectedIndex != null,
        )
    }
}

/**
 * A whole number in [range], typed into a number field (see [NumberField]) with [suffix] after it, such as "sat/vB". A number inside
 * the range is applied at once through [onValid], and [value] follows it. Any other text stays on the field, and the range is named
 * under it. [value] is null while the saved number is read, and then the field is off.
 */
@Composable
fun NumberFieldSetting(
    title: String,
    value: Int?,
    range: IntRange,
    suffix: String,
    onValid: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val refused = text.toIntOrNull()?.takeIf { it in range } == null && (text.isNotEmpty() || value != null)
    NumberField(
        title = title,
        text = text,
        onText = { typed ->
            text = typed
            // Applied in the same step as the typing, so a number inside the range is saved at once.
            val number = typed.toIntOrNull()?.takeIf { it in range }
            if (number != null && number != value) onValid(number)
        },
        suffix = suffix,
        modifier = modifier,
        enabled = value != null,
        error = if (refused) "Enter a number from ${range.first} to ${range.last}." else null,
    )
}

/**
 * A whole number typed into an outlined field under [title], with [suffix] after the digits, such as "accounts". The field shows
 * [text] and uses the number keyboard. Each edit that is digits only, and at most [MAX_NUMBER_DIGITS] of them, is passed to [onText];
 * any other edit is dropped, so the field keeps its text. A non-null [error] marks the field and is shown under it. [enabled] is
 * false while the number cannot be changed.
 */
@Composable
fun NumberField(
    title: String,
    text: String,
    onText: (String) -> Unit,
    suffix: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = LenslySpacing.md)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LenslySpacing.xs))
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                if (typed.length <= MAX_NUMBER_DIGITS && typed.all { it.isDigit() }) onText(typed)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            suffix = { Text(text = suffix) },
            supportingText = {
                if (error != null) Text(text = error)
            },
        )
    }
}

/**
 * A setting with a few choices, shown as one row, "[label] · [value]", with an arrow. A tap opens a dialog of radio rows. A
 * choice is applied at once and closes the dialog, and Cancel closes it without a change. [value] is null while the saved choice is
 * read, and the row then shows a progress mark. [description] says what the setting does, and [dialogTitle] names the dialog. A row
 * that is not [enabled] is dimmed and does not open.
 */
@Composable
fun <T> ChoiceDialogRow(
    label: String,
    value: String?,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    dialogTitle: String = label,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    SettingsItem {
        ListRow(onClick = { open = true }, enabled = enabled && value != null, modifier = modifier) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (value == null) label else "$label · $value",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (description != null) {
                    Spacer(Modifier.height(LenslySpacing.xxs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(LenslySpacing.sm))
            if (value == null) DotProgress(description = "Loading $label") else RowArrow()
        }
    }
    if (open && value != null) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = {
                Text(text = dialogTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            },
            text = {
                Column {
                    options.forEach { option ->
                        ChoiceOption(
                            label = optionLabel(option),
                            selected = option == selected,
                            onSelect = {
                                open = false
                                onSelect(option)
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextAction(text = "Cancel", onClick = { open = false }) },
        )
    }
}

/** One radio row of a [ChoiceDialogRow]'s dialog: the radio, then the option's label on one line. */
@Composable
private fun ChoiceOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
