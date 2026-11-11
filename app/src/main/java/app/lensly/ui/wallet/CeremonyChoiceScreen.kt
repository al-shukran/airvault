package app.lensly.ui.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily

/**
 * Start of the seed ceremony. The device has already committed to its randomness, so the commitment
 * is shown first (tap to expand). The owner then mixes in dice rolls or coin flips, or skips.
 *
 * [initiallyExpanded] sets whether the commitment starts open. Previews and screenshot tests use it.
 */
@Composable
fun CeremonyChoiceScreen(
    commitmentHex: String,
    onDice: () -> Unit,
    onCoins: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    LenslyScreen(
        title = "Seed ceremony",
        modifier = modifier,
        bottomAction = {
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
                ) {
                    PillButton(
                        text = "Roll dice",
                        onClick = onDice,
                        accent = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WalletEyebrow("About ${EntropyTargets.DICE_MINIMUM} rolls")
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
                ) {
                    PillButton(
                        text = "Flip coins",
                        onClick = onCoins,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WalletEyebrow("${EntropyTargets.COIN_MINIMUM} flips")
                }
            }
            Spacer(Modifier.height(LenslySpacing.sm))
            PillButton(text = "Skip", onClick = onSkip, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "MIX IN YOURS")
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "The device has already committed to its randomness.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        Text(
            text = "Your rolls or flips are mixed in on top of it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        CommitmentPanel(
            commitmentHex = commitmentHex,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
    }
}

/** The commitment in a panel. A tap opens it to the full hash, and a second tap folds it back to a preview. */
@Composable
private fun CommitmentPanel(commitmentHex: String, expanded: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    WalletPanel(
        modifier = Modifier
            .pressFeedback(interaction, enabled = true)
            .tapHaptic(haptics)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle),
    ) {
        WalletEyebrow(if (expanded) "Commitment. Tap to collapse" else "Commitment. Tap to expand")
        if (expanded) {
            HashLines(hex = commitmentHex)
        } else {
            Text(
                text = previewHex(commitmentHex),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
