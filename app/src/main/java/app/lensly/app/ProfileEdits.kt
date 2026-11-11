package app.lensly.app

/** The text field of a profile editor that takes the keys. */
enum class ProfileField { NAME, LABEL, GROUP }

/** The field of a batch style editor that takes the keys. A batch does not rename, so there is no name field. */
enum class StyleField { LABEL, GROUP }

/** Appends [key] to [text] unless the text is already [max] characters long. */
private fun appended(text: String, key: Char, max: Int): String = if (text.length >= max) text else text + key

/**
 * The editor state of one account. The name starts as the Display Name, which is empty while the account shows its Auto-Name.
 * [toProfile] gives the profile to save: a blank name is the Auto-Name, a blank label or group is none, and a colour or icon
 * that the account would generate anyway is not stored, so a reset leaves nothing behind.
 */
data class AccountDraft(
    val name: String,
    val colour: ProfileColour,
    val icon: ProfileIcon,
    val label: String,
    val group: String,
    val focus: ProfileField? = null,
) {
    /** Makes [field] the field that takes the keys, or none when it is null. */
    fun focusOn(field: ProfileField?): AccountDraft = copy(focus = field)

    /** The draft after [key] is typed into the focused field, which stops at its length. With no field focused, nothing changes. */
    fun typing(key: Char): AccountDraft = when (focus) {
        null -> this
        ProfileField.NAME -> copy(name = appended(name, key, DISPLAY_NAME_MAX_LENGTH))
        ProfileField.LABEL -> copy(label = appended(label, key, PROFILE_TEXT_MAX_LENGTH))
        ProfileField.GROUP -> copy(group = appended(group, key, PROFILE_TEXT_MAX_LENGTH))
    }

    fun backspace(): AccountDraft = when (focus) {
        null -> this
        ProfileField.NAME -> copy(name = name.dropLast(1))
        ProfileField.LABEL -> copy(label = label.dropLast(1))
        ProfileField.GROUP -> copy(group = group.dropLast(1))
    }

    fun withColour(colour: ProfileColour): AccountDraft = copy(colour = colour)

    fun withIcon(icon: ProfileIcon): AccountDraft = copy(icon = icon)

    /** Puts the account in [group], which is typed or picked from the groups the wallet already has. */
    fun withGroup(group: String): AccountDraft = copy(group = group)

    /** The name is cleared, so the account shows its Auto-Name again. */
    fun resetName(): AccountDraft = copy(name = "")

    /** Colour and icon go back to the generated identity, and the label and group are cleared. The name stays. */
    fun resetStyle(generated: VisualIdentity): AccountDraft =
        copy(colour = generated.colour, icon = generated.icon, label = "", group = "")

    /** The profile this draft saves, against the identity the account would generate for its index. */
    fun toProfile(generated: VisualIdentity): AccountProfile = AccountProfile(
        name = name.trim().ifEmpty { null },
        colour = colour.takeIf { it != generated.colour },
        icon = icon.takeIf { it != generated.icon },
        label = label.trim().ifEmpty { null },
        group = group.trim().ifEmpty { null },
    )

    companion object {
        /** The draft that starts from [profile]: the colour and icon it has, or the generated ones where it has none. */
        fun of(profile: AccountProfile, generated: VisualIdentity): AccountDraft = AccountDraft(
            name = profile.name.orEmpty(),
            colour = profile.colour ?: generated.colour,
            icon = profile.icon ?: generated.icon,
            label = profile.label.orEmpty(),
            group = profile.group.orEmpty(),
        )
    }
}

/**
 * The editor state of one wallet. The name starts as the Display Name, and a blank name keeps the current one, because a wallet
 * always has a name. The groups the wallet is in are listed, and the text typed into the group field is added when the draft
 * is saved. A wallet sits in at most [WALLET_MAX_GROUPS] groups.
 */
data class WalletProfileDraft(
    val name: String,
    val colour: ProfileColour,
    val icon: ProfileIcon,
    val label: String,
    val groups: List<String>,
    val pendingGroup: String = "",
    val focus: ProfileField? = null,
) {
    fun focusOn(field: ProfileField?): WalletProfileDraft = copy(focus = field)

    /**
     * The draft after [key] is typed into the focused field. The group field takes nothing once the wallet is at its cap, and with no
     * field focused nothing changes.
     */
    fun typing(key: Char): WalletProfileDraft = when (focus) {
        null -> this
        ProfileField.NAME -> copy(name = appended(name, key, DISPLAY_NAME_MAX_LENGTH))
        ProfileField.LABEL -> copy(label = appended(label, key, PROFILE_TEXT_MAX_LENGTH))
        ProfileField.GROUP -> if (groups.size >= WALLET_MAX_GROUPS) {
            this
        } else {
            copy(pendingGroup = appended(pendingGroup, key, PROFILE_TEXT_MAX_LENGTH))
        }
    }

    fun backspace(): WalletProfileDraft = when (focus) {
        null -> this
        ProfileField.NAME -> copy(name = name.dropLast(1))
        ProfileField.LABEL -> copy(label = label.dropLast(1))
        ProfileField.GROUP -> copy(pendingGroup = pendingGroup.dropLast(1))
    }

    fun withColour(colour: ProfileColour): WalletProfileDraft = copy(colour = colour)

    fun withIcon(icon: ProfileIcon): WalletProfileDraft = copy(icon = icon)

    /** Takes the wallet out of [group]. */
    fun removeGroup(group: String): WalletProfileDraft = copy(groups = groups - group)

    /** Colour, icon, label and groups go back to their defaults. The name stays. */
    fun resetStyle(generated: VisualIdentity): WalletProfileDraft = copy(
        colour = generated.colour,
        icon = generated.icon,
        label = "",
        groups = emptyList(),
        pendingGroup = "",
    )

    /** The name to save: the typed name, or [current] when the typed name is blank. */
    fun nameToSave(current: String): String = name.trim().ifEmpty { current }

    /** The groups to save: the ones the wallet is in and the one typed, without repeats, at most [WALLET_MAX_GROUPS]. */
    fun groupsToSave(): List<String> =
        (groups + pendingGroup.trim()).filter { it.isNotEmpty() }.distinct().take(WALLET_MAX_GROUPS)

    /** The profile this draft saves, against the identity the wallet would generate for its place in the list. */
    fun toProfile(generated: VisualIdentity): WalletProfile = WalletProfile(
        colour = colour.takeIf { it != generated.colour },
        icon = icon.takeIf { it != generated.icon },
        label = label.trim().ifEmpty { null },
        groups = groupsToSave(),
    )

    companion object {
        /** The draft that starts from [profile] and the wallet's [name]: the colour and icon it has, or the generated ones. */
        fun of(name: String, profile: WalletProfile, generated: VisualIdentity): WalletProfileDraft = WalletProfileDraft(
            name = name,
            colour = profile.colour ?: generated.colour,
            icon = profile.icon ?: generated.icon,
            label = profile.label.orEmpty(),
            groups = profile.groups,
        )
    }
}

/** What a batch style editor has been asked to set for one text field. */
sealed interface TextEdit {
    /** Every selected account keeps its text. */
    data object Keep : TextEdit

    /** The text is removed from every selected account. */
    data object Clear : TextEdit

    /** The text is set to [text] on every selected account. Blank text changes nothing. */
    data class Set(val text: String) : TextEdit
}

/**
 * What a batch style editor has been asked to set. A field nobody has touched is left as each account has it, so the batch
 * changes only what the owner picked or typed. [changes] gives them in the order they apply.
 */
data class StyleEdits(
    val colour: ProfileColour? = null,
    val icon: ProfileIcon? = null,
    val label: TextEdit = TextEdit.Keep,
    val group: TextEdit = TextEdit.Keep,
    val focus: StyleField? = null,
) {
    fun focusOn(field: StyleField?): StyleEdits = copy(focus = field)

    /** Picking the colour that is already picked un-picks it, so the batch can leave the colour alone. */
    fun pickColour(colour: ProfileColour): StyleEdits = copy(colour = if (this.colour == colour) null else colour)

    /** Picking the icon that is already picked un-picks it, so the batch can leave the icon alone. */
    fun pickIcon(icon: ProfileIcon): StyleEdits = copy(icon = if (this.icon == icon) null else icon)

    /** The text of [field] is removed from every selected account. */
    fun clear(field: StyleField): StyleEdits = when (field) {
        StyleField.LABEL -> copy(label = TextEdit.Clear)
        StyleField.GROUP -> copy(group = TextEdit.Clear)
    }

    /** The edit after [key] is typed into the focused field, which stops at the label length. With no field focused, nothing changes. */
    fun typing(key: Char): StyleEdits = when (focus) {
        null -> this
        StyleField.LABEL -> copy(label = TextEdit.Set(appended(label.textOrEmpty(), key, PROFILE_TEXT_MAX_LENGTH)))
        StyleField.GROUP -> copy(group = TextEdit.Set(appended(group.textOrEmpty(), key, PROFILE_TEXT_MAX_LENGTH)))
    }

    fun backspace(): StyleEdits = when (focus) {
        null -> this
        StyleField.LABEL -> copy(label = TextEdit.Set(label.textOrEmpty().dropLast(1)))
        StyleField.GROUP -> copy(group = TextEdit.Set(group.textOrEmpty().dropLast(1)))
    }

    /** The changes to apply, in order: colour, icon, label, group. Empty when nothing is set. */
    fun changes(): List<StyleChange> = buildList {
        colour?.let { add(StyleChange.Colour(it)) }
        icon?.let { add(StyleChange.Icon(it)) }
        when (label) {
            TextEdit.Keep -> Unit
            TextEdit.Clear -> add(StyleChange.Label(null))
            is TextEdit.Set -> label.text.trim().takeIf { it.isNotEmpty() }?.let { add(StyleChange.Label(it)) }
        }
        when (group) {
            TextEdit.Keep -> Unit
            TextEdit.Clear -> add(StyleChange.Group(null))
            is TextEdit.Set -> group.text.trim().takeIf { it.isNotEmpty() }?.let { add(StyleChange.Group(it)) }
        }
    }

    private fun TextEdit.textOrEmpty(): String = (this as? TextEdit.Set)?.text.orEmpty()
}
