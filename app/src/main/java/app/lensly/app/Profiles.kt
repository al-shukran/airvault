package app.lensly.app

/**
 * The fixed palette of Visual Identity colours. Each one reads on black, so a dot or an avatar of it shows on the page.
 * A record stores [wire], which never changes, so a record written now still reads after the palette is reordered.
 */
enum class ProfileColour(val wire: Int) {
    RED(1),
    ORANGE(2),
    YELLOW(3),
    GREEN(4),
    TEAL(5),
    BLUE(6),
    VIOLET(7),
    PINK(8);

    companion object {
        /** The colour stored as [wire], or null for a code this build does not know, which reads as the default. */
        fun fromWire(wire: Int): ProfileColour? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The fixed set of Visual Identity icons. Each is a simple glyph from the Material core set, so the app needs no icon
 * library beyond the one it has. A record stores [wire], as for [ProfileColour].
 */
enum class ProfileIcon(val wire: Int) {
    STAR(1),
    HEART(2),
    HOME(3),
    PERSON(4),
    PIN(5),
    WRENCH(6),
    SEND(7),
    THUMB_UP(8),
    BELL(9),
    CART(10),
    CALENDAR(11),
    PHONE(12);

    companion object {
        /** The icon stored as [wire], or null for a code this build does not know, which reads as the default. */
        fun fromWire(wire: Int): ProfileIcon? = entries.firstOrNull { it.wire == wire }
    }
}

/** A colour and an icon together: the Visual Identity of one account or wallet. */
data class VisualIdentity(val colour: ProfileColour, val icon: ProfileIcon)

/**
 * The Visual Identity generated for the entry at [position] of its list, counted from 0. Colours cycle through the palette,
 * and the icon advances once per full cycle of colours. So the first 96 positions, which is every colour with every icon,
 * all differ, and fifty entries look different at a glance.
 */
fun generatedIdentity(position: Int): VisualIdentity {
    require(position >= 0) { "A position is never negative." }
    val colours = ProfileColour.entries
    val icons = ProfileIcon.entries
    return VisualIdentity(
        colour = colours[position % colours.size],
        icon = icons[(position / colours.size) % icons.size],
    )
}

/**
 * The Visual Identity an entry shows: its own [colour] and [icon] where it has them, and the generated ones for [position]
 * where it does not. Resetting an entry's look gives it the generated identity back.
 */
fun visualIdentity(colour: ProfileColour?, icon: ProfileIcon?, position: Int): VisualIdentity {
    val generated = generatedIdentity(position)
    return VisualIdentity(colour = colour ?: generated.colour, icon = icon ?: generated.icon)
}

/** The longest label or group name, in characters after trimming. A label is a short tag, so the limit is short. */
const val PROFILE_TEXT_MAX_LENGTH = 16

/** The most groups one wallet sits in. A wallet's groups are listed on its screen, so the number stays small. */
const val WALLET_MAX_GROUPS = 8

/** True when [text] is a label or a group name: 1 to [PROFILE_TEXT_MAX_LENGTH] characters, with no control character. */
fun isValidProfileText(text: String): Boolean =
    text.length in 1..PROFILE_TEXT_MAX_LENGTH && text.none { it.isISOControl() }

/** The label or group name [raw] gives once it is trimmed. @throws IllegalArgumentException if that is not a valid name. */
fun profileTextOf(raw: String): String {
    val text = raw.trim()
    require(isValidProfileText(text)) {
        "A label or group is 1 to $PROFILE_TEXT_MAX_LENGTH characters, with no control character."
    }
    return text
}

/**
 * The display record of one account. A null field is the default: the Auto-Name for [name], the generated Visual Identity
 * for [colour] and [icon], and no label and no group. An account whose profile is empty has no record at all.
 */
data class AccountProfile(
    val name: String? = null,
    val colour: ProfileColour? = null,
    val icon: ProfileIcon? = null,
    val label: String? = null,
    val group: String? = null,
) {
    /** True when every field is its default, so the account needs no record. */
    val isEmpty: Boolean
        get() = name == null && colour == null && icon == null && label == null && group == null

    /** This profile after each of [changes] has been applied, in order. A change never touches the Display Name. */
    fun applying(changes: List<StyleChange>): AccountProfile =
        changes.fold(this) { profile, change -> change.applyTo(profile) }
}

/**
 * One change to the look of an account: its colour, icon, label or group. A batch applies a list of them, in order, to each
 * selected account, and no change touches the Display Name.
 */
sealed interface StyleChange {
    /** The profile this change leaves [profile] as. */
    fun applyTo(profile: AccountProfile): AccountProfile

    data class Colour(val colour: ProfileColour) : StyleChange {
        override fun applyTo(profile: AccountProfile): AccountProfile = profile.copy(colour = colour)
    }

    data class Icon(val icon: ProfileIcon) : StyleChange {
        override fun applyTo(profile: AccountProfile): AccountProfile = profile.copy(icon = icon)
    }

    /** Sets the label, or clears it when [label] is null. */
    data class Label(val label: String?) : StyleChange {
        override fun applyTo(profile: AccountProfile): AccountProfile = profile.copy(label = label)
    }

    /** Puts the account in [group], or takes it out of its group when [group] is null. */
    data class Group(val group: String?) : StyleChange {
        override fun applyTo(profile: AccountProfile): AccountProfile = profile.copy(group = group)
    }

    /** Colour, icon, label and group go back to their defaults. The Display Name stays. */
    data object Reset : StyleChange {
        override fun applyTo(profile: AccountProfile): AccountProfile =
            profile.copy(colour = null, icon = null, label = null, group = null)
    }
}

/** This change with its label or group name trimmed and checked. @throws IllegalArgumentException if not valid. */
internal fun StyleChange.checked(): StyleChange = when (this) {
    is StyleChange.Label -> StyleChange.Label(label?.let(::profileTextOf))
    is StyleChange.Group -> StyleChange.Group(group?.let(::profileTextOf))
    else -> this
}

/** This profile with its Display Name, label and group trimmed and checked. @throws IllegalArgumentException if not valid. */
internal fun AccountProfile.checked(): AccountProfile = copy(
    name = name?.let(::displayNameOf),
    label = label?.let(::profileTextOf),
    group = group?.let(::profileTextOf),
)

/**
 * The display record of one wallet beyond its name: its colour, icon, label and the groups it sits in. A null field is the
 * default, and a wallet whose profile is empty has no record.
 */
data class WalletProfile(
    val colour: ProfileColour? = null,
    val icon: ProfileIcon? = null,
    val label: String? = null,
    val groups: List<String> = emptyList(),
) {
    /** True when every field is its default, so the wallet needs no record. */
    val isEmpty: Boolean
        get() = colour == null && icon == null && label == null && groups.isEmpty()
}

/**
 * This profile with its label and groups trimmed and checked, and its groups de-duplicated.
 * @throws IllegalArgumentException if a name is not valid, or there are too many groups.
 */
internal fun WalletProfile.checked(): WalletProfile {
    val names = groups.map(::profileTextOf).distinct()
    require(names.size <= WALLET_MAX_GROUPS) { "A wallet sits in at most $WALLET_MAX_GROUPS groups." }
    return copy(label = label?.let(::profileTextOf), groups = names)
}
