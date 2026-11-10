package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.SpaceMonoFamily

/**
 * Transaction review. Every fact is in one scrollable list, and APPROVE stays disabled until the
 * user has scrolled to the end, so no fact can be skipped. A list that fits on screen counts as
 * read. Warnings sit at the top in red outlined cards.
 *
 * A personal message ([SIGN_MESSAGE_FUNCTION]) has no recipient, amount, nonce or fee. Its review
 * shows the message text in a monospace block instead of those facts.
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LenslyBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        DotHeadline(text = "REVIEW")
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { viewportPx = it.height }
                .verticalScroll(scroll),
        ) {
            Column(modifier = Modifier.fillMaxWidth().onSizeChanged { contentPx = it.height }) {
                review.warnings.forEach { warning ->
                    WarningCard(text = warning)
                    Spacer(Modifier.height(8.dp))
                }
                if (!isMessage) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The native coin shows its chain's logo. A token shows its own logo.
                        if (review.tokenContract == null) {
                            ChainIcon(network = review.network, size = 32.dp)
                        } else {
                            TokenIcon(token = review.token, size = 32.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${review.amount} ${review.symbol}",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                FactRow(label = "Network") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChainIcon(network = review.network, size = 20.dp)
                        Text(
                            text = review.networkName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        review.feeNote?.let { note -> MutedText(note) }
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
                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        if (!everyFactShown) {
            Text(
                text = "Scroll to the end to approve",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                enabled = everyFactShown,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, LenslyAccent, shape)
            .padding(16.dp),
    ) {
        Text(
            text = "Warning",
            style = MaterialTheme.typography.labelSmall,
            color = LenslyAccent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
            .padding(vertical = 12.dp),
    ) {
        MutedText(label.uppercase())
        Spacer(Modifier.height(6.dp))
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** An address with the Ethereum logo beside it. Every address shows that logo, whichever chain it is used on. */
@Composable
private fun AddressText(address: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AddressIcon(size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = groupAddress(address),
            style = MaterialTheme.typography.bodyMedium,
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
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
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
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        MutedText(label.uppercase())
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isEvmAddress(value)) {
                AddressIcon(size = 16.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
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
 * Pill that matches the app's PillButton look. It can be disabled, which PillButton cannot do yet.
 * A filled pill is white, an outlined one is transparent with a thin border.
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
            .clip(shape)
            .background(fill)
            .then(if (filled) Modifier else Modifier.border(1.dp, colors.outline, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
        )
    }
}
