package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily

/**
 * Transaction review. The top block names the wallet, account, network and origin that will sign. Every fact is in one
 * scrollable list, and the hold-to-sign control stays disabled until the user has scrolled to the end, so no fact can be
 * skipped. A list that fits on screen counts as read. Warnings sit at the top as red banners.
 *
 * A personal message ([SIGN_MESSAGE_FUNCTION]) has no recipient, amount, nonce or fee. Its review shows the message in a
 * monospace block, with its size, instead of those facts. The message is part of the list, so the scroll covers it.
 *
 * A review outside the registry ([TxReview.needsRegistryConfirmation]) opens with a solid warning block, and signing also
 * needs the "I CHECKED THE CHAIN ID / CONTRACT" check to be ON. A whitelisted call ([TxReview.whitelisted]) opens with its
 * approved meaning and every fact of the call, and signing needs its own check; a proxy under Warn and confirm needs a second.
 * The line above the buttons says what is still missing.
 *
 * REJECT is always enabled. Nothing is ellipsized: every value on this screen is shown in full, and wraps when it must.
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
    // The checks the review asks for, such as the chain ID and contract outside the registry. They reset with the review.
    var checked by remember(review) { mutableStateOf(emptySet<ReviewCheck>()) }
    val approvable = review.canApprove(everyFactShown, checked)

    LenslyScreen(
        title = "Review",
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            ReviewHint(text = reviewHint(review, everyFactShown, checked))
            review.requiredChecks.forEach { check ->
                CheckRow(
                    label = check.label,
                    checked = check in checked,
                    onCheckedChange = { on -> checked = if (on) checked + check else checked - check },
                )
                Spacer(Modifier.height(LenslySpacing.sm))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                RejectPill(onClick = onReject, modifier = Modifier.weight(1f))
                HoldToSignButton(enabled = approvable, onSign = onApprove, modifier = Modifier.weight(1f))
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
                    if (review.denylisted.isNotEmpty()) {
                        DenylistWarning(reasons = review.denylisted.map { it.reason })
                        Spacer(Modifier.height(LenslySpacing.sm))
                    }
                    if (review.needsRegistryConfirmation) {
                        RegistryWarning()
                        Spacer(Modifier.height(LenslySpacing.sm))
                    }
                    review.whitelisted?.let { whitelisted ->
                        WhitelistedCallBlock(whitelisted)
                        Spacer(Modifier.height(LenslySpacing.sm))
                    }
                    review.warnings.forEach { warning ->
                        InlineBanner(message = warning, tone = BannerTone.Error, title = "Warning")
                        Spacer(Modifier.height(LenslySpacing.xs))
                    }
                    if (!isMessage && review.amount.isNotEmpty()) {
                        AmountRow(review)
                        Spacer(Modifier.height(LenslySpacing.xs))
                    }

                    SigningHeader(review)

                    // A whitelisted call names its contract in the block above, so the recipient row would repeat it.
                    if (!isMessage && review.whitelisted == null && review.toAddress.isNotEmpty()) {
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
                    review.signature?.let { signature ->
                        FactRow(label = "Signature") {
                            MonoText(text = signature)
                        }
                    }
                    val typed = review.typed
                    if (isMessage) {
                        MessageFacts(review)
                    } else if (typed != null) {
                        TypedDataFacts(typed)
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
                        if (review.nested.isNotEmpty()) {
                            FactRow(label = "Calls") {
                                review.nested.forEach { NestedCallView(it) }
                            }
                        }
                        FactRow(label = "Fee breakdown") {
                            review.fee.forEach { (name, value) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    MutedText(name.uppercase())
                                    MonoText(text = value)
                                }
                            }
                            review.feeNote?.let { note -> NoteText(note) }
                        }
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
 * The line above the buttons. It names what still stands between the owner and signing, and holds its height when there
 * is nothing to say, so the buttons do not move.
 */
private fun reviewHint(review: TxReview, everyFactShown: Boolean, checked: Set<ReviewCheck>): String? {
    val owed = review.requiredChecks.count { it !in checked }
    return when {
        !everyFactShown -> "Scroll to the end to review everything"
        owed == 1 -> "Tick the check below to sign"
        owed > 1 -> "Tick the checks below to sign"
        else -> null
    }
}

/** The text on the row of a check. */
private val ReviewCheck.label: String
    get() = when (this) {
        ReviewCheck.DENYLIST -> "I UNDERSTAND THIS IS A RISKY REQUEST"
        ReviewCheck.REGISTRY -> "I CHECKED THE CHAIN ID / CONTRACT"
        ReviewCheck.WHITELIST -> "I CHECKED THIS IS WHAT I INTEND"
        ReviewCheck.UPGRADE -> "I ACCEPT THE IMPLEMENTATION CANNOT BE CHECKED"
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
 * Who signs, and where. The wallet and the account are shown with their Visual Identity, and the account's full address
 * follows its name. The network has its logo, and the origin the request names is shown as the requester gave it.
 */
@Composable
private fun SigningHeader(review: TxReview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LenslyRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = LenslySpacing.sm),
    ) {
        FactRow(label = "Wallet") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(identity = review.walletIdentity, size = 32.dp)
                Spacer(Modifier.width(LenslySpacing.sm))
                Text(
                    text = review.walletName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FactRow(label = "Account") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(identity = review.accountIdentity, size = 24.dp)
                Spacer(Modifier.width(LenslySpacing.sm))
                Text(
                    text = review.fromAccountLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(LenslySpacing.xs))
            AddressText(address = review.fromAddress)
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
        FactRow(label = "Requested by") {
            val origin = review.origin
            Text(
                text = origin ?: NOT_STATED,
                style = MaterialTheme.typography.bodyLarge,
                color = if (origin == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
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
            text = listOf(review.amount, review.symbol).filter { it.isNotEmpty() }.joinToString(" "),
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
 * The first thing on a review that matches the denylist of known-dangerous requests. A solid block that gives the reason of each
 * match, so the owner reads why the request is risky before the check that lets it be signed.
 */
@Composable
private fun DenylistWarning(reasons: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LenslyRadius.lg))
            .background(LenslyAccent)
            .padding(LenslySpacing.md),
    ) {
        Text(
            text = "KNOWN RISKY REQUEST",
            style = MaterialTheme.typography.headlineSmall,
            color = LenslyOnSurface,
        )
        reasons.forEach { reason ->
            Spacer(Modifier.height(LenslySpacing.xs))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = LenslyOnSurface,
            )
        }
    }
}

/**
 * An explicit check the review asks for, such as the chain ID and contract of a review outside the registry. It sits above the
 * buttons, outside the scroll, so it stays in view, and the hold-to-sign control is disabled until it is ON. A red outline
 * means the check is still owed.
 */
@Composable
internal fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
            text = label,
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

/**
 * A typed message (EIP-712): its domain, the summary of a Permit or Permit2 payload when it is one, and every field of the message.
 * The fields keep their nesting, so a struct's members sit under its header, one level in.
 */
@Composable
private fun TypedDataFacts(typed: TypedReview) {
    FactRow(label = "Domain") {
        typed.domain.forEach { (name, value) -> LabelledValue(label = name, value = value) }
    }
    if (typed.summary.isNotEmpty()) {
        FactRow(label = "Summary") {
            typed.summary.forEach { (name, value) -> LabelledValue(label = name, value = value) }
        }
    }
    FactRow(label = "Fields") {
        typed.fields.forEach { row -> TypedFieldLine(row) }
    }
}

/** One line of a typed message, indented by its depth. A header line names a struct or an array and its type. */
@Composable
private fun TypedFieldLine(row: TypedRow) {
    val value = row.value
    Column(modifier = Modifier.padding(start = (row.depth * 12).dp, bottom = LenslySpacing.xs)) {
        MutedText("${row.label} · ${row.type}".uppercase())
        if (value != null) {
            Spacer(Modifier.height(LenslySpacing.xxs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isEvmAddress(value)) {
                    AddressIcon(size = 16.dp)
                    Spacer(Modifier.width(LenslySpacing.xs))
                }
                MonoText(text = if (isEvmAddress(value)) groupAddress(value) else value, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * A personal message: its text in the monospace face on a raised block, so each character can be checked by eye. Long
 * lines wrap inside the block, and its size follows it.
 */
@Composable
private fun MessageFacts(review: TxReview) {
    val text = review.params.firstOrNull { it.first == "message" }?.second.orEmpty()
    val size = review.params.firstOrNull { it.first == "size" }?.second
    FactRow(label = "Message") {
        MessageBlock(text = text)
    }
    if (size != null) {
        FactRow(label = "Size") {
            Text(
                text = size,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

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

/**
 * One call of a multicall, indented by its level so the nesting reads at a glance. It opens with the same warnings a lone call
 * gets: the denylist block for a known-dangerous request, the registry block for a token outside the registry, then each warning.
 * Then come its target when it names one, its function and arguments, and its note. A call the whitelist approves ends with its
 * approved meaning, under the same block a lone call gets.
 */
@Composable
private fun NestedCallView(call: NestedCall) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LenslySpacing.md * (call.depth - 1), bottom = LenslySpacing.sm),
    ) {
        if (call.denylisted.isNotEmpty()) {
            DenylistWarning(reasons = call.denylisted.map { it.reason })
            Spacer(Modifier.height(LenslySpacing.xs))
        }
        if (call.unregistered) {
            RegistryWarning()
            Spacer(Modifier.height(LenslySpacing.xs))
        }
        call.warnings.forEach { warning ->
            InlineBanner(message = warning, tone = BannerTone.Error, title = "Warning")
            Spacer(Modifier.height(LenslySpacing.xs))
        }
        call.target?.let { target ->
            MutedText("TARGET")
            Spacer(Modifier.height(LenslySpacing.xxs))
            AddressText(address = target)
            Spacer(Modifier.height(LenslySpacing.xs))
        }
        Text(
            text = call.function,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        call.params.forEach { (name, value) -> LabelledValue(label = name, value = value) }
        call.note?.let { note -> NoteText(note) }
        call.whitelisted?.let { whitelisted ->
            Spacer(Modifier.height(LenslySpacing.xs))
            WhitelistedCallBlock(whitelisted)
        }
    }
}

/** One argument of a call: its name above its value. An address is shown in groups of four, so it can be compared by eye. */
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
            MonoText(text = if (isEvmAddress(value)) groupAddress(value) else value, modifier = Modifier.weight(1f))
        }
    }
}

/** REJECT: an outlined pill that is always enabled. It shrinks while pressed and plays the key haptic. */
@Composable
internal fun RejectPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(56.dp)
            .pressFeedback(interaction, enabled = true)
            .clip(shape)
            .border(1.dp, colors.outline, shape)
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "REJECT",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
        )
    }
}
