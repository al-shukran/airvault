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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyOnSurface

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

private const val SLOTS_PER_ROW = 3

/**
 * Restore flow: the owner types word #1 to #24 through [SecureKeyboard], and the screen lists
 * suggestions above the keyboard. Keyboard contract for the caller: a letter appends to [RestoreState.prefix];
 * SPACE or a suggestion tap commits the typed word; a tap on a filled chip calls [onEditWord] with its index;
 * DONE calls [onSubmit], which the caller uses to validate the full phrase. Once all 24 words are in, a
 * CHECK WORDS button also calls [onSubmit].
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
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SuggestionRow(suggestions = state.suggestions, onPick = onPickSuggestion)
                SecureKeyboard(
                    onKey = onKey,
                    onBackspace = onBackspace,
                    onDone = onSubmit,
                )
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        WalletEyebrow("Word ${minOf(state.activeIndex + 1, count)} of $count")
        Spacer(Modifier.height(8.dp))
        DotHeadline(text = "RESTORE")
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (state.prefix.isEmpty()) {
                Text(
                    text = "Type word ${minOf(state.activeIndex + 1, count)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = state.prefix.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = LenslyOnSurface,
                )
            }
        }
        WalletError(message = if (state.invalidChecksum) "Invalid checksum" else null)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (0 until count).chunked(SLOTS_PER_ROW).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { index ->
                        WordSlot(
                            index = index,
                            state = state,
                            onEdit = onEditWord,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (complete) {
                WalletPill(
                    text = "Check words",
                    onClick = onSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    style = WalletPillStyle.Primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
            .height(48.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
 * active slot has a white outline. An empty slot shows only its number.
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
            .height(36.dp)
            .clip(shape)
            .background(if (filled) colors.surfaceVariant else Color.Transparent, shape)
            .border(1.dp, if (active) LenslyOnSurface else colors.outline, shape)
            .then(
                if (filled) Modifier.clickable(role = Role.Button) { onEdit(index) }
                else Modifier,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%02d".format(index + 1),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            if (filled) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = state.words[index].uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
