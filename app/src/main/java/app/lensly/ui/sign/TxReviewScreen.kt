package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily

/**
 * Transaction review. Every fact is in one scrollable list, and APPROVE stays disabled until the
 * user has scrolled to the end, so no fact can be skipped. A list that fits on screen counts as
 * read. Warnings sit at the top as red banners.
 *
 * A personal message ([SIGN_MESSAGE_FUNCTION]) has no recipient, amount, nonce or fee. Its review
 * shows the message text in a monospace block instead of those facts.
 *
 * A review outside the registry ([TxReview.needsRegistryConfirmation]) opens with a solid warning
 * block, and APPROVE also needs the "I CHECKED THE CHAIN ID / CONTRACT" check to be ON. The line
 * above the buttons says what is still missing.
 *
 * REJECT is always enabled.
 */
@Composable
fun TxReviewScreen(
    review: TxReview,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val isMessage = review.function == SIGN_MESSAGE_FUNCTION
    var viewportPx by remember { mutableIntStateOf(0) }
    var contentPx by remember { mutableIntStateOf(0) }
    val atEnd by remember {
        derivedStateOf { scroll.maxValue > 0 && scroll.value >= scroll.maxValue }
    }
    val fits by remember {
        derivedStateOf { viewportPx > 0 && contentPx in 1..viewportPx }
    }
    // Sticky once reached, and reset when a different transaction is shown.
    var everyFactShown by remember(review) { mutableStateOf(false) }
    LaunchedEffect(atEnd, fits) {
        if (atEnd || fits) everyFactShown = true
    }
    // The check of the chain ID and contract. Only a review outside the registry asks for it. It resets with the review.
    var registryChecked by remember(review) { mutableStateOf(false) }
    val approvable = review.canApprove(everyFactShown, registryChecked)

    LenslyScreen(
        title = "Review",
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            ReviewHint(text = reviewHint(review, everyFactShown, registryChecked))
            if (review.needsRegistryConfirmation) {
                RegistryCheckRow(checked = registryChecked, onCheckedChange = { registryChecked = it })
                Spacer(Modifier.height(LenslySpacing.sm))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                ReviewPill(
                    text = "Reject",
                    onClick = onReject,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
                ReviewPill(
                    text = "Approve",
                    onClick = onApprove,
                    filled = true,
                    enabled = approvable,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(scroll),
            ) {
            Column(modifier = Modifier.fillMaxWidth().onSizeChanged { contentPx = it.height }) {
                if (review.needsRegistryConfirmation) {
                    RegistryWarning()
                    Spacer(Modifier.height(LenslySpacing.sm))
                }
                review.warnings.forEach { warning ->
                    InlineBanner(message = warning, tone = BannerTone.Error, title = "Warning")
                    Spacer(Modifier.height(LenslySpacing.xs))
                }
                if (!isMessage) {
                    AmountRow(review)
                    Spacer(Modifier.height(LenslySpacing.xs))
                }

                FactRow(label = "Network") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
                    ) {
                        ChainIcon(network = review.network, size = 20.dp)
                        Text(
                            text = review.networkName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // A testnet can share its logo with a mainnet, so the logo alone does not say which it is.
                        if (review.network?.testnet == true) PillChip(text = "Testnet")
                        PillChip(text = "Chain ${review.chainId}")
                    }
                }
                FactRow(label = "From") {
                    Text(
                        text = review.fromAccountLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    AddressText(address = review.fromAddress)
                }
                if (!isMessage) {
                    FactRow(label = "To") {
                        AddressText(address = review.toAddress)
                    }
                }
                review.tokenContract?.let { contract ->
                    if (review.token == null) {
                        FactRow(label = "Token") {
                            Text(
                                text = UNKNOWN_TOKEN_NAME,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    FactRow(label = "Token contract") {
                        AddressText(address = contract)
                    }
                }
                FactRow(label = "Function") {
                    Text(
                        text = review.function,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (isMessage) {
                    FactRow(label = "Message") {
                        MessageBlock(text = review.params.firstOrNull()?.second.orEmpty())
                    }
                } else {
                    FactRow(label = "Parameters") {
                        if (review.params.isEmpty()) {
                            MutedText("None")
                        } else {
                            review.params.forEach { (name, value) ->
                                LabelledValue(label = name, value = value)
                            }
                        }
                    }
                    FactRow(label = "Fee breakdown") {
                        review.fee.forEach { (name, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                MutedText(name.uppercase())
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        review.feeNote?.let { note -> NoteText(note) }
                    }
                }
                if (!isMessage) {
                    FactRow(label = "Nonce") {
                        Text(
                            text = review.nonce.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                FactRow(label = "Type") {
                    Text(
                        text = review.txType,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(LenslySpacing.md))
            }
            }
            // A fade over the cut-off edge while more facts lie below, so the list reads as scrolling, not clipped.
            if (!atEnd) {
                ScrollFade(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** The fade at the bottom of the fact list. It runs from transparent to the page colour, so it sits over the content. */
@Composable
private fun ScrollFade(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LenslySpacing.xl)
            .background(Brush.verticalGradient(listOf(Color.Transparent, LenslyBlack))),
    )
}

/**
 * The line above the buttons. It names what still stands between the owner and APPROVE, and holds its height when there
 * is nothing to say, so the buttons do not move.
 */
private fun reviewHint(review: TxReview, everyFactShown: Boolean, registryChecked: Boolean): String? = when {
    !everyFactShown -> "Scroll to the end to approve"
    review.needsRegistryConfirmation && !registryChecked -> "Tick the check below to approve"
    else -> null
}

@Composable
private fun ReviewHint(text: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LenslySpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The amount as the headline of the review, with the coin or token logo beside it. An amount in raw base units is set
 * smaller, so a long integer stays on one line. The muted note under it says how the amount was read.
 */
@Composable
private fun AmountRow(review: TxReview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The native coin shows its chain's logo. A token shows its logo, or the unknown-token icon, with its
        // chain's logo as a badge when it is a pegged or bridged copy.
        if (review.tokenContract == null) {
            ChainIcon(network = review.network, size = 32.dp)
        } else {
            TokenIcon(token = review.token, size = 32.dp, chain = review.network)
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = "${review.amount} ${review.symbol}",
            style = if (review.amountNote == null) {
                MaterialTheme.typography.displaySmall
            } else {
                MaterialTheme.typography.headlineSmall
            },
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
    review.amountNote?.let { note ->
        Spacer(Modifier.height(LenslySpacing.xxs))
        NoteText(note)
    }
}

/**
 * The first thing on a review whose chain or token is outside the registry. A solid accent block, so it cannot be
 * missed, with its title in the dot-matrix face.
 */
@Composable
private fun RegistryWarning() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LenslyRadius.lg))
            .background(LenslyAccent)
            .padding(LenslySpacing.md),
    ) {
        Text(
            text = "NOT IN LENSLY'S REGISTRY",
            style = MaterialTheme.typography.headlineSmall,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "Lensly cannot confirm this network or token, so check the chain ID and contract " +
                "against a trusted source.",
            style = MaterialTheme.typography.bodyMedium,
            color = LenslyOnSurface,
        )
    }
}

/**
 * The explicit check for a review outside the registry. It sits above the buttons, outside the scroll, so it stays in
 * view, and APPROVE is disabled until it is ON. A red outline means the check is still owed.
 */
@Composable
private fun RegistryCheckRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val haptics = rememberKeyHaptics()
    val pill = RoundedCornerShape(percent = 50)
    val owed = if (checked) LenslyOnSurface else LenslyError
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(pill)
            .border(1.dp, owed, pill)
            .tapHaptic(haptics)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(start = LenslySpacing.md, end = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "I CHECKED THE CHAIN ID / CONTRACT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(LenslySpacing.sm))
        Box(
            modifier = Modifier
                .clip(pill)
                .background(if (checked) LenslyOnSurface else Color.Transparent)
                .border(1.dp, owed, pill)
                .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.xs - LenslySpacing.xxs),
        ) {
            Text(
                text = if (checked) "ON" else "OFF",
                style = MaterialTheme.typography.labelSmall,
                color = if (checked) LenslyBlack else LenslyError,
            )
        }
    }
}

/** One fact: a muted uppercase label above its value, with a divider underneath. */
@Composable
private fun FactRow(
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.sm),
    ) {
        MutedText(label.uppercase())
        Spacer(Modifier.height(LenslySpacing.xs))
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/**
 * An address in the monospace face, so each character can be compared by eye, with the Ethereum logo beside it. Every
 * address shows that logo, whichever chain it is used on.
 */
@Composable
private fun AddressText(address: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AddressIcon(size = 16.dp)
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            text = groupAddress(address),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The text of a message in the monospace face on a raised block, so each character can be checked by eye. Long
 * lines wrap inside the block.
 */
@Composable
private fun MessageBlock(text: String) {
    val shape = RoundedCornerShape(LenslyRadius.md)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(LenslySpacing.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = LenslySpacing.xs)) {
        MutedText(label.uppercase())
        Spacer(Modifier.height(LenslySpacing.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isEvmAddress(value)) {
                AddressIcon(size = 16.dp)
                Spacer(Modifier.width(LenslySpacing.xs))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A sentence of explanation, such as a fee or amount note. It uses the sans body face, which reads better than the tracked label face. */
@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MutedText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A pill for REJECT or APPROVE. A filled pill is white, an outlined one is transparent with a thin border. A disabled
 * pill is grey and ignores taps. It is not PillButton, which cannot be white. It shrinks while pressed and plays the key
 * haptic when it is enabled.
 */
@Composable
private fun ReviewPill(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(percent = 50)
    val fill: Color = when {
        filled && enabled -> LenslyOnSurface
        filled -> colors.surfaceVariant
        else -> Color.Transparent
    }
    val labelColor: Color = when {
        !enabled -> colors.onSurfaceVariant
        filled -> LenslyBlack
        else -> colors.onSurface
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .pressIfEnabled(interaction, enabled)
            .clip(shape)
            .background(fill)
            .then(if (filled) Modifier else Modifier.border(1.dp, colors.outline, shape))
            .tapHaptic(haptics, enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
        )
    }
}

/** Press feedback only for an enabled pill. A disabled one already reads as disabled, so it does not also shrink. */
@Composable
private fun Modifier.pressIfEnabled(interaction: InteractionSource, enabled: Boolean): Modifier =
    if (enabled) pressFeedback(interaction, enabled = true) else this
