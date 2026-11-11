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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import app.lensly.ui.components.ProfileIcons
import app.lensly.ui.components.profileIconSections
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

/**
 * The second half of the profile palette: eight colours between and beside the theme's eight, each light enough that a black
 * icon reads on it and each at least 25 apart from every other in R, G and B. They live here rather than in the theme because
 * only a profile uses them.
 */
private val ProfileLime = Color(0xFFB4E052)
private val ProfileMint = Color(0xFF5FE8B0)
private val ProfileCyan = Color(0xFF3FC9F5)
private val ProfileIndigo = Color(0xFF7A7BFF)
private val ProfileMagenta = Color(0xFFE879F9)
private val ProfileApricot = Color(0xFFFFB067)
private val ProfileRose = Color(0xFFFF9AC4)
private val ProfilePlum = Color(0xFFB06BFF)

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
    ProfileColour.LIME -> ProfileLime
    ProfileColour.MINT -> ProfileMint
    ProfileColour.CYAN -> ProfileCyan
    ProfileColour.INDIGO -> ProfileIndigo
    ProfileColour.MAGENTA -> ProfileMagenta
    ProfileColour.APRICOT -> ProfileApricot
    ProfileColour.ROSE -> ProfileRose
    ProfileColour.PLUM -> ProfilePlum
}

/** The glyph of an icon: the twelve of the Material core set, and the drawn ones for the rest. */
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
    ProfileIcon.COIN -> ProfileIcons.Coin
    ProfileIcon.COINS -> ProfileIcons.Coins
    ProfileIcon.VAULT -> ProfileIcons.Vault
    ProfileIcon.BANK -> ProfileIcons.Bank
    ProfileIcon.CARD -> ProfileIcons.Card
    ProfileIcon.BANKNOTE -> ProfileIcons.Banknote
    ProfileIcon.CHART_UP -> ProfileIcons.ChartUp
    ProfileIcon.PIE_CHART -> ProfileIcons.PieChart
    ProfileIcon.RECEIPT -> ProfileIcons.Receipt
    ProfileIcon.PURSE -> ProfileIcons.Purse
    ProfileIcon.SCALE -> ProfileIcons.Scale
    ProfileIcon.TAG -> ProfileIcons.Tag
    ProfileIcon.GEM -> ProfileIcons.Gem
    ProfileIcon.ROCKET -> ProfileIcons.Rocket
    ProfileIcon.SHIELD -> ProfileIcons.Shield
    ProfileIcon.LANTERN -> ProfileIcons.Lantern
    ProfileIcon.LINK -> ProfileIcons.Link
    ProfileIcon.NODE -> ProfileIcons.Node
    ProfileIcon.CUBE -> ProfileIcons.Cube
    ProfileIcon.SPARK -> ProfileIcons.Spark
    ProfileIcon.SWAP -> ProfileIcons.Swap
    ProfileIcon.CHIP -> ProfileIcons.Chip
    ProfileIcon.GLOBE -> ProfileIcons.Globe
    ProfileIcon.WAVES -> ProfileIcons.Waves
    ProfileIcon.LEAF -> ProfileIcons.Leaf
    ProfileIcon.TREE -> ProfileIcons.Tree
    ProfileIcon.FLOWER -> ProfileIcons.Flower
    ProfileIcon.SUN -> ProfileIcons.Sun
    ProfileIcon.MOON -> ProfileIcons.Moon
    ProfileIcon.FLAME -> ProfileIcons.Flame
    ProfileIcon.DROP -> ProfileIcons.Drop
    ProfileIcon.BOOK -> ProfileIcons.Book
    ProfileIcon.COFFEE -> ProfileIcons.Coffee
    ProfileIcon.BRIEFCASE -> ProfileIcons.Briefcase
    ProfileIcon.LAMP -> ProfileIcons.Lamp
    ProfileIcon.BED -> ProfileIcons.Bed
    ProfileIcon.DICE -> ProfileIcons.Dice
    ProfileIcon.MUSIC -> ProfileIcons.Music
    ProfileIcon.PLANE -> ProfileIcons.Plane
    ProfileIcon.CAR -> ProfileIcons.Car
    ProfileIcon.GIFT -> ProfileIcons.Gift
    ProfileIcon.CAMERA -> ProfileIcons.Camera
    ProfileIcon.TROPHY -> ProfileIcons.Trophy
    ProfileIcon.BICYCLE -> ProfileIcons.Bicycle
    ProfileIcon.TENT -> ProfileIcons.Tent
    ProfileIcon.BALL -> ProfileIcons.Ball
    ProfileIcon.HEADPHONES -> ProfileIcons.Headphones
    ProfileIcon.TICKET -> ProfileIcons.Ticket
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

/** The colours that fit on one line of the picker: eight, so sixteen wrap into two lines at the narrowest phone width. */
private const val COLOURS_PER_LINE = 8

/** The icons that fit on one line of the picker: six 44 dp tiles. */
private const val ICONS_PER_LINE = 6

/**
 * The sixteen colours in two rows of eight, the chosen one ringed. [selected] is null while no colour is chosen, as in a batch
 * that keeps each account's colour. A tap calls [onPick].
 */
@Composable
internal fun ProfileColourRow(selected: ProfileColour?, onPick: (ProfileColour) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        ProfileColour.entries.chunked(COLOURS_PER_LINE).forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                line.forEach { colour ->
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
    }
}

/**
 * Every icon, in six labelled sections of a grid of six, the chosen one on a lighter tile and ringed. The sections keep the set
 * readable once it is long, and the page scrolls, so all of them are reachable. [selected] is null while none is chosen. A tap
 * calls [onPick].
 */
@Composable
internal fun ProfileIconRow(selected: ProfileIcon?, onPick: (ProfileIcon) -> Unit, modifier: Modifier = Modifier) {
    val tile = RoundedCornerShape(LenslyRadius.md)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        profileIconSections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
                WalletEyebrow(section.label)
                section.icons.chunked(ICONS_PER_LINE).forEach { line ->
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
    }
}

/**
 * One text field of a profile editor: its name above, the count of its characters while it takes the keys, and its value in a
 * box. The box is outlined while it takes the keys, and empty text shows [placeholder] in muted type. A tap on the box calls
 * [onFocus], which makes it the field the keyboard types into. A field that takes the keys scrolls into view, so the keyboard
 * does not cover it.
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
    val scrollIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) scrollIntoView.bringIntoView() }
    Column(modifier = modifier.fillMaxWidth().bringIntoViewRequester(scrollIntoView)) {
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
