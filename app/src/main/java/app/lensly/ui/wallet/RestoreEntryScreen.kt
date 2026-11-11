package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer

/**
 * UI state for [RestoreEntryScreen]. [words] are the committed words, where index 0 is word #1.
 * [activeIndex] is the word being typed. It equals words.size for a new word, and points at an
 * existing chip while that word is being re-edited. [prefix] is what has been typed for it so far.
 * [suggestions] are at most four BIP39 words that start with the prefix, from [Bip39Autocomplete].
 * [invalidChecksum] comes from the caller's validation of the full 24 words.
 */
data class RestoreState(
    val words: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val prefix: String = "",
    val suggestions: List<String> = emptyList(),
    val invalidChecksum: Boolean = false,
)

private const val TITLE = "Restore wallet"
private const val SLOTS_PER_ROW = 3
private val SLOT_HEIGHT = 40.dp
private val SLOT_GAP = 8.dp
private val INPUT_HEIGHT = 56.dp
private val HELPER_HEIGHT = 20.dp
private val SUGGESTION_HEIGHT = 56.dp

/**
 * Restore flow: the owner types word #1 to #24 through [SecureKeyboard], and the screen lists
 * suggestions above the keyboard. Keyboard contract for the caller: a letter appends to [RestoreState.prefix];
 * SPACE or a suggestion tap commits the typed word; a tap on a filled chip calls [onEditWord] with its index;
 * DONE calls [onSubmit], which the caller uses to validate the full phrase. Once all 24 words are in, a
 * CHECK WORDS button also calls [onSubmit]. The keyboard is up when the screen opens. A tap outside the word field or the
 * system Back hides it, and a tap on the field, or on a chip to edit its word, brings it back.
 */
@Composable
fun RestoreEntryScreen(
    state: RestoreState,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    onEditWord: (Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = Mnemonic.WORD_COUNT
    val complete = state.words.size >= count
    val current = minOf(state.activeIndex + 1, count)
    // A prefix that no BIP39 word starts with can never become a word, so the field says so at once.
    val noMatch = state.prefix.isNotEmpty() && state.suggestions.isEmpty()
    var focused by rememberSaveable { mutableStateOf(true) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        LenslyScreen(
            title = TITLE,
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                if (complete) {
                    WalletPill(
                        text = "Check words",
                        onClick = onSubmit,
                        modifier = Modifier.fillMaxWidth(),
                        style = WalletPillStyle.Primary,
                    )
                } else {
                    SuggestionRow(suggestions = state.suggestions, onPick = onPickSuggestion)
                }
                Spacer(Modifier.height(LenslySpacing.sm))
                BottomSlot(visible = focused) {
                    SecureKeyboard(
                        onKey = onKey,
                        onBackspace = onBackspace,
                        onDone = onSubmit,
                    )
                }
            },
        ) {
            WalletEyebrow("Word $current of $count")
            Spacer(Modifier.height(LenslySpacing.xs))
            StepSegments(count = count, filled = state.words.size)
            Spacer(Modifier.height(LenslySpacing.md))
            PrefixField(
                prefix = state.prefix,
                placeholder = if (complete) "All $count words entered" else "Type word $current",
                invalid = noMatch,
                focused = focused,
                onFocus = { focused = true },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HELPER_HEIGHT),
                contentAlignment = Alignment.CenterStart,
            ) {
                when {
                    noMatch -> Text(
                        text = "No word starts with these letters",
                        style = MaterialTheme.typography.labelMedium,
                        color = LenslyError,
                    )
                    state.prefix.isNotEmpty() -> Text(
                        text = "Tap a suggestion, or press space",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.invalidChecksum) {
                Spacer(Modifier.height(LenslySpacing.xs))
                InlineBanner(
                    title = "Invalid checksum",
                    message = "These 24 words do not form a valid phrase. Check each word, in order.",
                    tone = BannerTone.Error,
                )
            }
            Spacer(Modifier.height(LenslySpacing.sm))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SLOT_GAP),
            ) {
                (0 until count).chunked(SLOTS_PER_ROW).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SLOT_GAP),
                    ) {
                        row.forEach { index ->
                            WordSlot(
                                index = index,
                                state = state,
                                onEdit = { edited ->
                                    focused = true
                                    onEditWord(edited)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(LenslySpacing.xs))
        }
    }
}

/**
 * The word being typed. It shows a caret after the letters while it has focus, and turns red when no BIP39 word matches them.
 * A tap calls [onFocus].
 */
@Composable
private fun PrefixField(prefix: String, placeholder: String, invalid: Boolean, focused: Boolean, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(INPUT_HEIGHT)
            .clip(shape)
            .background(LenslySurfaceContainer, shape)
            .border(1.dp, if (invalid) LenslyError else LenslyOutlineInteractive, shape)
            .clickable(role = Role.Button, onClick = onFocus)
            .padding(horizontal = LenslySpacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (prefix.isEmpty()) {
            Text(
                text = placeholder.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (focused) "${prefix.uppercase()}_" else prefix.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = if (invalid) LenslyError else LenslyOnSurface,
            )
        }
    }
}

/** Suggestion pills above the keyboard. The row keeps its height when empty, so the keyboard does not move. */
@Composable
private fun SuggestionRow(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SUGGESTION_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEach { word ->
            WalletPill(
                text = word,
                onClick = { onPick(word) },
                modifier = Modifier.weight(1f),
                style = WalletPillStyle.Secondary,
                size = WalletPillSize.Compact,
            )
        }
    }
}

/**
 * One of the 24 slots. A filled slot shows its number and word, and tapping it edits that word. The
 * active slot has a white outline. An empty slot shows only its number. Every slot is the same width, and the
 * number sits in a fixed-width column, so the words line up down the grid.
 */
@Composable
private fun WordSlot(
    index: Int,
    state: RestoreState,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val filled = index < state.words.size
    val active = index == state.activeIndex
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(SLOT_HEIGHT)
            .clip(shape)
            .background(if (filled) colors.surfaceVariant else Color.Transparent, shape)
            .border(1.dp, if (active) LenslyOnSurface else colors.outline, shape)
            .then(
                if (filled) Modifier.clickable(role = Role.Button) { onEdit(index) }
                else Modifier,
            )
            .padding(horizontal = LenslySpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(SLOT_NUMBER_WIDTH)) {
                Text(
                    text = "%02d".format(index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (filled) {
                Text(
                    text = state.words[index].uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private val SLOT_NUMBER_WIDTH = 20.dp
