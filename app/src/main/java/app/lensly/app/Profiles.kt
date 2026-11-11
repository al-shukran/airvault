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
    PINK(8),
    LIME(9),
    MINT(10),
    CYAN(11),
    INDIGO(12),
    MAGENTA(13),
    APRICOT(14),
    ROSE(15),
    PLUM(16);

    companion object {
        /** The colour stored as [wire], or null for a code this build does not know, which reads as the default. */
        fun fromWire(wire: Int): ProfileColour? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The fixed set of Visual Identity icons. The first twelve are simple glyphs from the Material core set; the rest are drawn
 * in the app, so it needs no icon library beyond the one it has. A record stores [wire], as for [ProfileColour]. The list
 * only grows: a wire written by an older build keeps its icon.
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
    PHONE(12),
    COIN(13),
    COINS(14),
    VAULT(15),
    BANK(16),
    CARD(17),
    BANKNOTE(18),
    CHART_UP(19),
    PIE_CHART(20),
    RECEIPT(21),
    PURSE(22),
    SCALE(23),
    TAG(24),
    GEM(25),
    ROCKET(26),
    SHIELD(27),
    LOCK(28),
    LINK(29),
    NODE(30),
    CUBE(31),
    SPARK(32),
    SWAP(33),
    CHIP(34),
    GLOBE(35),
    WAVES(36),
    LEAF(37),
    TREE(38),
    FLOWER(39),
    SUN(40),
    MOON(41),
    FLAME(42),
    DROP(43),
    BOOK(44),
    COFFEE(45),
    BRIEFCASE(46),
    LAMP(47),
    BED(48),
    DICE(49),
    MUSIC(50),
    PLANE(51),
    CAR(52),
    GIFT(53),
    CAMERA(54),
    TROPHY(55),
    BICYCLE(56),
    TENT(57),
    BALL(58),
    HEADPHONES(59),
    TICKET(60);

    companion object {
        /** The icon stored as [wire], or null for a code this build does not know, which reads as the default. */
        fun fromWire(wire: Int): ProfileIcon? = entries.firstOrNull { it.wire == wire }
    }
}

/** A colour and an icon together: the Visual Identity of one account or wallet. */
data class VisualIdentity(val colour: ProfileColour, val icon: ProfileIcon)

/**
 * The number of colours a generated identity cycles through: the first eight of the palette. The palette has grown since the
 * first build, but an account that was never styled must keep the look it already had, so the generated identity still uses
 * only these. Nothing here changes when the palette grows.
 */
const val GENERATED_COLOURS = 8

/**
 * The number of icons a generated identity cycles through: the first twelve of the icon set. Kept for the same reason as
 * [GENERATED_COLOURS]: an unstyled account must not change look when the icon set grows.
 */
const val GENERATED_ICONS = 12

/**
 * The Visual Identity generated for the entry at [position] of its list, counted from 0. Colours cycle through the first
 * [GENERATED_COLOURS] of the palette, and the icon advances through the first [GENERATED_ICONS] once per full cycle of
 * colours. So the first 96 positions, which is every one of those colours with every one of those icons, all differ, and
 * fifty entries look different at a glance.
 */
fun generatedIdentity(position: Int): VisualIdentity {
    require(position >= 0) { "A position is never negative." }
    return VisualIdentity(
        colour = ProfileColour.entries[position % GENERATED_COLOURS],
        icon = ProfileIcon.entries[(position / GENERATED_COLOURS) % GENERATED_ICONS],
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
