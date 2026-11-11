package app.lensly.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileIcon
import app.lensly.app.VisualIdentity
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyProfileBlue
import app.lensly.ui.theme.LenslyProfileGreen
import app.lensly.ui.theme.LenslyProfileOrange
import app.lensly.ui.theme.LenslyProfilePink
import app.lensly.ui.theme.LenslyProfileRed
import app.lensly.ui.theme.LenslyProfileTeal
import app.lensly.ui.theme.LenslyProfileViolet
import app.lensly.ui.theme.LenslyProfileYellow
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslySurfaceHighest
import app.lensly.ui.wallet.WalletEyebrow

/** The swatch of a palette colour. */
internal fun ProfileColour.swatch(): Color = when (this) {
    ProfileColour.RED -> LenslyProfileRed
    ProfileColour.ORANGE -> LenslyProfileOrange
    ProfileColour.YELLOW -> LenslyProfileYellow
    ProfileColour.GREEN -> LenslyProfileGreen
    ProfileColour.TEAL -> LenslyProfileTeal
    ProfileColour.BLUE -> LenslyProfileBlue
    ProfileColour.VIOLET -> LenslyProfileViolet
    ProfileColour.PINK -> LenslyProfilePink
}

/** The glyph of an icon, from the Material core set. */
internal fun ProfileIcon.glyph(): ImageVector = when (this) {
    ProfileIcon.STAR -> Icons.Filled.Star
    ProfileIcon.HEART -> Icons.Filled.Favorite
    ProfileIcon.HOME -> Icons.Filled.Home
    ProfileIcon.PERSON -> Icons.Filled.Person
    ProfileIcon.PIN -> Icons.Filled.Place
    ProfileIcon.WRENCH -> Icons.Filled.Build
    ProfileIcon.SEND -> Icons.AutoMirrored.Filled.Send
    ProfileIcon.THUMB_UP -> Icons.Filled.ThumbUp
    ProfileIcon.BELL -> Icons.Filled.Notifications
    ProfileIcon.CART -> Icons.Filled.ShoppingCart
    ProfileIcon.CALENDAR -> Icons.Filled.DateRange
    ProfileIcon.PHONE -> Icons.Filled.Phone
}

/** The name a screen reader says for a colour, such as "Teal". */
internal val ProfileColour.spokenName: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }

/** The name a screen reader says for an icon, such as "Thumb up". */
internal val ProfileIcon.spokenName: String
    get() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/** A coloured disc with its icon in black: the Visual Identity of one account or wallet. It is decorative, so the name beside it is read. */
@Composable
internal fun ProfileAvatar(identity: VisualIdentity, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(identity.colour.swatch()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = identity.icon.glyph(),
            contentDescription = null,
            tint = LenslyBlack,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * The eight colours in one row, the chosen one ringed. [selected] is null while no colour is chosen, as in a batch that keeps
 * each account's colour. A tap calls [onPick].
 */
@Composable
internal fun ProfileColourRow(selected: ProfileColour?, onPick: (ProfileColour) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ProfileColour.entries.forEach { colour ->
            val isSelected = colour == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colour.swatch())
                    .then(if (isSelected) Modifier.border(3.dp, LenslyOnSurface, CircleShape) else Modifier)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(colour) })
                    .semantics { contentDescription = colour.spokenName },
            )
        }
    }
}

/**
 * The twelve icons in two rows of six, the chosen one on a lighter tile and ringed. [selected] is null while none is chosen. A
 * tap calls [onPick].
 */
@Composable
internal fun ProfileIconRow(selected: ProfileIcon?, onPick: (ProfileIcon) -> Unit, modifier: Modifier = Modifier) {
    val tile = RoundedCornerShape(LenslyRadius.md)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        ProfileIcon.entries.chunked(6).forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                line.forEach { icon ->
                    val isSelected = icon == selected
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(tile)
                            .background(if (isSelected) LenslySurfaceHighest else LenslySurfaceContainer)
                            .then(if (isSelected) Modifier.border(2.dp, LenslyOnSurface, tile) else Modifier)
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(icon) })
                            .semantics { contentDescription = icon.spokenName },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon.glyph(),
                            contentDescription = null,
                            tint = LenslyOnSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One text field of a profile editor: its name above, the count of its characters while it takes the keys, and its value in a
 * box. The box is outlined while it takes the keys, and empty text shows [placeholder] in muted type. A tap on the box calls
 * [onFocus], which makes it the field the keyboard types into.
 */
@Composable
internal fun ProfileFieldRow(
    label: String,
    value: String,
    placeholder: String,
    focused: Boolean,
    maxLength: Int,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletEyebrow(label)
            Spacer(Modifier.weight(1f))
            if (focused) WalletEyebrow("${value.length}/$maxLength")
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(LenslySurfaceContainer, shape)
                .border(1.dp, if (focused) LenslyOnSurface else LenslyOutline, shape)
                .clickable(role = Role.Button, onClick = onFocus)
                .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.sm),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                value.isNotEmpty() -> Text(
                    text = if (focused) "${value}_" else value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Text(
                    text = if (focused) "_" else placeholder,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
