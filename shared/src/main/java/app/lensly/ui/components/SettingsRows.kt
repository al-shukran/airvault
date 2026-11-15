package app.lensly.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow

/** The opacity of a row that is switched off. */
private const val DISABLED_ROW_ALPHA = 0.4f

/** One section: a muted label, then its rows. Every section starts the same distance below the one before. */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xl)) {
        WalletEyebrow(title)
        Spacer(Modifier.height(LenslySpacing.xs))
        content()
    }
}

/** Wraps a settings row or block with the one divider that closes it. */
@Composable
fun SettingsItem(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The one row style of the settings: a title, an optional description, and a trailing mark. A row with [onClick] shows an
 * arrow and presses like a button. A [loading] row shows a progress mark in place of the arrow and the [preview]. A [preview]
 * sits between the text and the arrow. A [destructive] row names an action that removes something, in the error colour.
 */
@Composable
fun SettingsRow(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    loading: Boolean = false,
    preview: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    SettingsItem {
        ListRow(onClick = onClick, enabled = enabled, modifier = modifier) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (destructive) MaterialTheme.colorScheme.error else LenslyOnSurface,
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
            if (loading) {
                Spacer(Modifier.width(LenslySpacing.sm))
                DotProgress(description = "Loading $title")
            } else {
                if (preview != null) {
                    Spacer(Modifier.width(LenslySpacing.sm))
                    preview()
                }
                if (onClick != null) {
                    Spacer(Modifier.width(LenslySpacing.sm))
                    RowArrow()
                }
            }
        }
    }
}

/**
 * A settings row that switches one setting on or off. The whole row is the toggle: a tap anywhere on it flips [checked], and the
 * switch shows the state. [description] says what turning it on does.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    SettingsItem {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.pressFeedback(interaction, enabled = true) else Modifier)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = interaction,
                    indication = null,
                    onValueChange = onCheckedChange,
                )
                .tapHaptic(haptics, enabled)
                .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ROW_ALPHA))
                .padding(vertical = LenslySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
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
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        footer?.invoke()
    }
}

/**
 * The content of a settings row: a row of its own children, with the row's padding and press feedback. A row with [onClick] presses
 * like a button and plays the key haptic. A row that is not [enabled] dims and ignores taps. [content] lays out the text and the marks.
 */
@Composable
fun ListRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    val tapModifier = if (onClick != null) {
        Modifier
            .pressFeedback(interaction, enabled = enabled)
            .tapHaptic(haptics, enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .then(tapModifier)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ROW_ALPHA))
            .padding(vertical = LenslySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The arrow that marks a row that opens another screen. */
@Composable
fun RowArrow() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * A text action in a row or a form: a flat text button in the text colour, or in the error colour when [destructive] names an action
 * that removes something. It dims while it is not [enabled].
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) colors.error else LenslyOnSurface,
            disabledContentColor = LenslyOnSurface.copy(alpha = DISABLED_ROW_ALPHA),
        ),
    ) {
        Text(text = text)
    }
}
