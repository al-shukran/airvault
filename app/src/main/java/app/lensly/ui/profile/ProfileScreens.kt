package app.lensly.ui.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.AccountDraft
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.PROFILE_TEXT_MAX_LENGTH
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileField
import app.lensly.app.ProfileIcon
import app.lensly.app.StyleEdits
import app.lensly.app.StyleField
import app.lensly.app.TextEdit
import app.lensly.app.VisualIdentity
import app.lensly.app.WALLET_MAX_GROUPS
import app.lensly.app.WalletProfileDraft
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle

/**
 * The profile editor of one account: its Display Name, label and group, then its colour and icon. The field that takes the keys
 * is outlined, and [onKey] and [onBackspace] feed it. A blank name is the Auto-Name, and DONE saves the whole profile. [groups] are
 * the groups the wallet's other accounts use, offered as quick picks. RESET NAME shows while the account has a name, and RESET
 * STYLE gives back the generated colour and icon and clears the label and group.
 */
@Composable
fun AccountProfileScreen(
    draft: AccountDraft,
    autoName: String,
    groups: List<String>,
    onFocus: (ProfileField) -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPickColour: (ProfileColour) -> Unit,
    onPickIcon: (ProfileIcon) -> Unit,
    onPickGroup: (String) -> Unit,
    onResetName: () -> Unit,
    onResetStyle: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Account profile",
        modifier = modifier,
        bottomAction = { SecureKeyboard(onKey = onKey, onBackspace = onBackspace, onDone = onDone) },
    ) {
        ProfileHeader(identity = VisualIdentity(draft.colour, draft.icon), title = draft.name.ifEmpty { autoName })
        Spacer(Modifier.height(LenslySpacing.lg))
        ProfileFieldRow(
            label = "Name",
            value = draft.name,
            placeholder = autoName,
            focused = draft.focus == ProfileField.NAME,
            maxLength = DISPLAY_NAME_MAX_LENGTH,
            onFocus = { onFocus(ProfileField.NAME) },
        )
        Spacer(Modifier.height(LenslySpacing.md))
        ProfileFieldRow(
            label = "Label",
            value = draft.label,
            placeholder = "No label",
            focused = draft.focus == ProfileField.LABEL,
            maxLength = PROFILE_TEXT_MAX_LENGTH,
            onFocus = { onFocus(ProfileField.LABEL) },
        )
        Spacer(Modifier.height(LenslySpacing.md))
        ProfileFieldRow(
            label = "Group",
            value = draft.group,
            placeholder = "No group",
            focused = draft.focus == ProfileField.GROUP,
            maxLength = PROFILE_TEXT_MAX_LENGTH,
            onFocus = { onFocus(ProfileField.GROUP) },
        )
        if (groups.isNotEmpty()) {
            Spacer(Modifier.height(LenslySpacing.xs))
            ChipLine {
                groups.forEach { group ->
                    WalletPill(
                        text = group,
                        onClick = { onPickGroup(group) },
                        style = WalletPillStyle.Outline,
                        size = WalletPillSize.Compact,
                    )
                }
            }
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Colour")
        Spacer(Modifier.height(LenslySpacing.xs))
        ProfileColourRow(selected = draft.colour, onPick = onPickColour)
        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Icon")
        Spacer(Modifier.height(LenslySpacing.xs))
        ProfileIconRow(selected = draft.icon, onPick = onPickIcon)
        Spacer(Modifier.height(LenslySpacing.lg))
        ChipLine {
            if (draft.name.isNotEmpty()) {
                WalletPill(text = "Reset name", onClick = onResetName, style = WalletPillStyle.Outline, size = WalletPillSize.Compact)
            }
            WalletPill(text = "Reset style", onClick = onResetStyle, style = WalletPillStyle.Outline, size = WalletPillSize.Compact)
        }
    }
}

/**
 * The profile editor of one wallet: its name, label and groups, then its colour and icon. A blank name keeps the current one, since a
 * wallet always has one. The group field adds the group typed when DONE saves, and a tap on a group takes the wallet out of it. A
 * wallet sits in at most [WALLET_MAX_GROUPS] groups.
 */
@Composable
fun WalletProfileScreen(
    draft: WalletProfileDraft,
    onFocus: (ProfileField) -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPickColour: (ProfileColour) -> Unit,
    onPickIcon: (ProfileIcon) -> Unit,
    onRemoveGroup: (String) -> Unit,
    onResetStyle: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val full = draft.groups.size >= WALLET_MAX_GROUPS
    LenslyScreen(
        title = "Wallet profile",
        modifier = modifier,
        bottomAction = { SecureKeyboard(onKey = onKey, onBackspace = onBackspace, onDone = onDone) },
    ) {
        ProfileHeader(identity = VisualIdentity(draft.colour, draft.icon), title = draft.name)
        Spacer(Modifier.height(LenslySpacing.lg))
        ProfileFieldRow(
            label = "Name",
            value = draft.name,
            placeholder = "Keep the current name",
            focused = draft.focus == ProfileField.NAME,
            maxLength = DISPLAY_NAME_MAX_LENGTH,
            onFocus = { onFocus(ProfileField.NAME) },
        )
        Spacer(Modifier.height(LenslySpacing.md))
        ProfileFieldRow(
            label = "Label",
            value = draft.label,
            placeholder = "No label",
            focused = draft.focus == ProfileField.LABEL,
            maxLength = PROFILE_TEXT_MAX_LENGTH,
            onFocus = { onFocus(ProfileField.LABEL) },
        )
        Spacer(Modifier.height(LenslySpacing.md))
        ProfileFieldRow(
            label = "Group",
            value = draft.pendingGroup,
            placeholder = if (full) "Eight groups at most" else "Add a group",
            focused = draft.focus == ProfileField.GROUP,
            maxLength = PROFILE_TEXT_MAX_LENGTH,
            onFocus = { onFocus(ProfileField.GROUP) },
        )
        if (draft.groups.isNotEmpty()) {
            Spacer(Modifier.height(LenslySpacing.xs))
            ChipLine {
                draft.groups.forEach { group ->
                    WalletPill(
                        text = group,
                        onClick = { onRemoveGroup(group) },
                        style = WalletPillStyle.Outline,
                        size = WalletPillSize.Compact,
                    )
                }
            }
            WalletEyebrow("Tap a group to take the wallet out of it")
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Colour")
        Spacer(Modifier.height(LenslySpacing.xs))
        ProfileColourRow(selected = draft.colour, onPick = onPickColour)
        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Icon")
        Spacer(Modifier.height(LenslySpacing.xs))
        ProfileIconRow(selected = draft.icon, onPick = onPickIcon)
        Spacer(Modifier.height(LenslySpacing.lg))
        ChipLine {
            WalletPill(text = "Reset style", onClick = onResetStyle, style = WalletPillStyle.Outline, size = WalletPillSize.Compact)
        }
    }
}

/**
 * The batch style editor: the colour, icon, label and group that every selected account gets, applied in one write on DONE. A
 * colour, icon, label or group that is not set here keeps each account's own. CLEAR removes a label or group from every selected
 * account, and RESET STYLE gives every selected account its generated look at once.
 */
@Composable
fun StyleAccountsScreen(
    count: Int,
    edits: StyleEdits,
    onFocus: (StyleField) -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPickColour: (ProfileColour) -> Unit,
    onPickIcon: (ProfileIcon) -> Unit,
    onClear: (StyleField) -> Unit,
    onResetStyle: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Account style",
        modifier = modifier,
        bottomAction = { SecureKeyboard(onKey = onKey, onBackspace = onBackspace, onDone = onDone) },
    ) {
        Text(
            text = "Set for $count accounts",
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "Pick what to set. Each account keeps what is not set here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Colour")
        Spacer(Modifier.height(LenslySpacing.xs))
        ProfileColourRow(selected = edits.colour, onPick = onPickColour)
        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Icon")
        Spacer(Modifier.height(LenslySpacing.xs))
        ProfileIconRow(selected = edits.icon, onPick = onPickIcon)
        Spacer(Modifier.height(LenslySpacing.lg))
        ProfileFieldRow(
            label = "Label",
            value = edits.label.shown(),
            placeholder = if (edits.label == TextEdit.Clear) "Cleared from every account" else "Keep each account's label",
            focused = edits.focus == StyleField.LABEL,
            maxLength = PROFILE_TEXT_MAX_LENGTH,
            onFocus = { onFocus(StyleField.LABEL) },
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        ChipLine {
            WalletPill(text = "Clear label", onClick = { onClear(StyleField.LABEL) }, style = WalletPillStyle.Outline, size = WalletPillSize.Compact)
        }
        Spacer(Modifier.height(LenslySpacing.md))
        ProfileFieldRow(
            label = "Group",
            value = edits.group.shown(),
            placeholder = if (edits.group == TextEdit.Clear) "Cleared from every account" else "Keep each account's group",
            focused = edits.focus == StyleField.GROUP,
            maxLength = PROFILE_TEXT_MAX_LENGTH,
            onFocus = { onFocus(StyleField.GROUP) },
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        ChipLine {
            WalletPill(text = "Clear group", onClick = { onClear(StyleField.GROUP) }, style = WalletPillStyle.Outline, size = WalletPillSize.Compact)
            WalletPill(text = "Reset style", onClick = onResetStyle, style = WalletPillStyle.Outline, size = WalletPillSize.Compact)
        }
    }
}

/** The avatar and the name of the thing being edited, at the top of its editor. */
@Composable
private fun ProfileHeader(identity: VisualIdentity, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(identity = identity, size = 48.dp)
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = title.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A line of pills that scrolls sideways when the pills are wider than the screen, so no pill is cut off. */
@Composable
private fun ChipLine(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The text a batch field shows: what is typed, or nothing while the field keeps or clears each account's text. */
private fun TextEdit.shown(): String = (this as? TextEdit.Set)?.text.orEmpty()
