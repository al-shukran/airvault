package app.lensly.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurface
import app.lensly.ui.wallet.WalletEyebrow

/** The filters of the Networks screen: every network, the mainnets, or the testnets. */
internal enum class NetworkFilter(val label: String) {
    ALL("All"),
    MAINNETS("Mainnets"),
    TESTNETS("Testnets");

    /** True when [network] is listed under this filter. */
    fun keeps(network: NetworkEntry): Boolean = when (this) {
        ALL -> true
        MAINNETS -> !network.testnet
        TESTNETS -> network.testnet
    }
}

/** The first rows fade in one after another, [WAVE_STEP_MS] apart. Later rows fade in at once, so scrolling is not held up. */
private const val WAVE_ROWS = 8
private const val WAVE_STEP_MS = 60
private const val REVEAL_MS = 250

/**
 * The networks Lensly can sign for, two to a row. A filter picks all of them, the mainnets or the testnets. A card shows the
 * network's logo, name, chain ID and native symbol, and a tap on it opens the fee model and the number of tokens the network
 * supports. The rows fade in as the screen first shows. [networks] and [tokens] are the catalog as read.
 */
@Composable
fun NetworksScreen(
    networks: List<NetworkEntry>,
    tokens: List<TokenEntry>,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(NetworkFilter.ALL) }
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    val tokenCounts = remember(tokens) { tokens.groupingBy { it.chainId }.eachCount() }
    val rows = networks.filter(filter::keeps).chunked(2)

    LenslyScreen(title = "Networks", modifier = modifier, onBack = onBack, scrollable = false) {
        CatalogIntro(
            text = "Lensly can sign for these networks offline. Others are signed with a warning.",
            onLock = onLock,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.md),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            for (option in NetworkFilter.entries) {
                FilterChip(
                    selected = option == filter,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = LenslySpacing.md),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            itemsIndexed(rows, key = { _, row -> row.first().chainId }) { index, row ->
                StaggeredReveal(index) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                        for (network in row) {
                            NetworkCard(
                                network = network,
                                tokenCount = tokenCounts[network.chainId] ?: 0,
                                expanded = network.chainId in expanded,
                                onToggle = { expanded = expanded.toggled(network.chainId) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size < 2) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The line under a catalog screen's title: the eyebrow with the Lock button beside it, then what the list means. */
@Composable
internal fun CatalogIntro(text: String, onLock: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletEyebrow("Offline vault", modifier = Modifier.weight(1f))
        PillButton(text = "Lock", onClick = onLock)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LenslySpacing.md),
    )
}

/** One network as a card. A tap toggles the part that shows the fee model and the token count. */
@Composable
private fun NetworkCard(
    network: NetworkEntry,
    tokenCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Column(
        modifier = modifier
            .pressFeedback(interaction, enabled = true)
            .clip(shape)
            .background(LenslySurface)
            .border(1.dp, LenslyOutline, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(LenslySpacing.md),
    ) {
        ChainIcon(network = network, size = 56.dp)
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = network.name,
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        Text(
            text = "Chain ID ${network.chainId}",
            style = MaterialTheme.typography.labelMedium,
            color = LenslyMuted,
        )
        Text(
            text = network.nativeSymbol,
            style = MaterialTheme.typography.labelMedium,
            color = LenslyMuted,
        )
        if (network.testnet) {
            Spacer(Modifier.height(LenslySpacing.xs))
            PillChip(text = "Testnet")
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = LenslySpacing.sm)) {
                Text(
                    text = "Fee model",
                    style = MaterialTheme.typography.labelSmall,
                    color = LenslyMuted,
                )
                Text(
                    text = network.feeModel.plainName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LenslyOnSurface,
                )
                Spacer(Modifier.height(LenslySpacing.xs))
                Text(
                    text = "Supported tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = LenslyMuted,
                )
                Text(
                    text = "$tokenCount ${if (tokenCount == 1) "token" else "tokens"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LenslyOnSurface,
                )
            }
        }
    }
}

/**
 * Fades [content] in when it first shows. The first rows start one after another, so the list arrives in a short wave.
 */
@Composable
private fun StaggeredReveal(index: Int, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val delay = if (index < WAVE_ROWS) index * WAVE_STEP_MS else 0
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(animationSpec = tween(durationMillis = REVEAL_MS, delayMillis = delay)),
    ) {
        content()
    }
}

/** The fee model in plain words. */
private fun FeeModel.plainName(): String = when (this) {
    FeeModel.L1 -> "Ethereum-style: a base fee and a priority fee, paid in the native coin"
    FeeModel.L2_ROLLUP -> "Rollup: an execution fee, and a fee for posting the data to Ethereum"
}

/** This set with [chainId] added when it is absent, and removed when it is present. */
private fun Set<Long>.toggled(chainId: Long): Set<Long> = if (chainId in this) this - chainId else this + chainId
