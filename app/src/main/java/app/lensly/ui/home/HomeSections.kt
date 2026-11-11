package app.lensly.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.ProfileColour
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.WalletArt
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslySurfaceHigh
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow

/** The test tag of one recent activity row, so a test can count how many Home shows. */
internal const val HOME_ACTIVITY_ROW_TAG = "home-activity-row"

/** The test tag of the Vault summary card, so a test can find it under the readiness chips. */
internal const val HOME_VAULT_CARD_TAG = "home-vault-card"

/** The width of one wallet in the Home carousel. It is shorter than the card on the Wallets list. */
private val PEEK_CARD_WIDTH = 216.dp

/** The number of recent records Home shows. */
private const val RECENT_LIMIT = 3

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val MONTH_MS = 2_592_000_000L

/**
 * The readiness of the vault, as the chips under the wallet carousel: how many networks and tokens the catalog holds, the
 * catalog's version when it has one, and the wrong password lockout. A null count is shown as zero, and a null label is left
 * off its chip.
 */
data class HomeReadiness(
    val networkCount: Int = 0,
    val tokenCount: Int = 0,
    val catalogVersion: String? = null,
    val lockout: String? = null,
)

/** The large primary action under the hero: the accent fill, a scan glyph and the words "Scan to sign". */
@Composable
internal fun ScanToSignButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        PillButton(text = "Scan to sign", onClick = onClick, accent = true, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The three newest Review Records of every wallet: the outcome, a short summary, the wallet's name and how long ago it was.
 * "See all" opens the Activity screen. With no record yet, one quiet line says so.
 */
@Composable
internal fun RecentActivitySection(
    records: List<WalletReviewRecord>,
    wallets: List<WalletMeta>,
    nowMs: Long,
    onSeeAll: () -> Unit,
) {
    SectionHeader(title = "Recent activity", action = "See all", onAction = onSeeAll)
    if (records.isEmpty()) {
        Text(
            text = "Nothing signed yet",
            modifier = Modifier.padding(vertical = LenslySpacing.xs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val walletNames = wallets.associate { it.id to it.name }
    records
        .sortedByDescending { it.record.atMs }
        .take(RECENT_LIMIT)
        .forEach { item ->
            RecentRow(
                record = item.record,
                walletName = walletNames[item.walletId],
                nowMs = nowMs,
            )
        }
}

/** One recent record: outcome icon, summary, wallet and relative time. */
@Composable
private fun RecentRow(record: ReviewRecord, walletName: String?, nowMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOME_ACTIVITY_ROW_TAG)
            .padding(vertical = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = outcomeIcon(record.outcome),
            contentDescription = outcomeLabel(record.outcome),
            tint = outcomeTint(record.outcome),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recordSummary(record),
                style = MaterialTheme.typography.titleSmall,
                color = LenslyOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(walletName, relativeTime(record.atMs, nowMs)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The wallet carousel: every wallet as a compact card, with the link to the full list. */
@Composable
internal fun WalletPeekSection(
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    onOpen: (String) -> Unit,
    onWallets: () -> Unit,
    onLongPress: (WalletMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Wallets",
            action = "All wallets →",
            onAction = onWallets,
            modifier = Modifier.padding(horizontal = WALLET_GUTTER),
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        // The row fills the screen and carries the gutter as content padding, so the next card peeks from the true edge.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = WALLET_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            itemsIndexed(wallets, key = { _, wallet -> wallet.id }) { index, wallet ->
                WalletCard(
                    wallet = wallet,
                    profile = profiles[wallet.id] ?: WalletProfile(),
                    position = index,
                    compact = true,
                    width = PEEK_CARD_WIDTH,
                    onClick = { onOpen(wallet.id) },
                    onLongPress = { onLongPress(wallet) },
                )
            }
        }
    }
}

/** The readiness chips. Each one opens the Settings screen that explains or changes it. */
@Composable
internal fun ReadinessStrip(
    readiness: HomeReadiness,
    onNetworks: () -> Unit,
    onTokens: () -> Unit,
    onCatalog: () -> Unit,
    onLockout: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        WalletEyebrow("Readiness")
        Spacer(Modifier.height(LenslySpacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            ReadinessChip(
                text = countOf(readiness.networkCount, "network", "networks"),
                ready = readiness.networkCount > 0,
                onClick = onNetworks,
            )
            ReadinessChip(
                text = countOf(readiness.tokenCount, "token", "tokens"),
                ready = readiness.tokenCount > 0,
                onClick = onTokens,
            )
            ReadinessChip(
                text = readiness.catalogVersion?.let { "Catalog $it" } ?: "Catalog",
                ready = readiness.catalogVersion != null,
                onClick = onCatalog,
            )
            ReadinessChip(
                text = readiness.lockout?.let { "Lockout $it" } ?: "Lockout",
                ready = readiness.lockout != null,
                onClick = onLockout,
            )
        }
    }
}

/** The status dot of a chip, at the accent when its setting is in place. */
private val CHIP_DOT = 6.dp

/** One small outlined chip that opens a settings screen, with [ready] shown as a filled dot. */
@Composable
private fun ReadinessChip(text: String, ready: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(LenslyPillShape)
            .border(1.dp, LenslyOutline, LenslyPillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LenslySpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(CHIP_DOT)
                    .clip(CircleShape)
                    .background(if (ready) LenslyAccent else LenslyMuted),
            )
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = LenslyOnSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * The vault at a glance, under the readiness chips: how many wallets and accounts it holds, when it last signed, and the date
 * of the catalog in use. Everything comes from what this session has already read, so a decoy session shows only its own data.
 */
@Composable
internal fun VaultSummaryCard(
    wallets: List<WalletMeta>,
    lastSignedAtMs: Long?,
    catalogDate: String?,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LenslyRadius.xl))
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyOutline, RoundedCornerShape(LenslyRadius.xl))
            .padding(LenslySpacing.md)
            .testTag(HOME_VAULT_CARD_TAG),
    ) {
        WalletEyebrow("Vault")
        Spacer(Modifier.height(LenslySpacing.sm))
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryFact(
                label = "Wallets",
                value = wallets.size.toString(),
                modifier = Modifier.weight(1f),
            )
            SummaryFact(
                label = "Accounts",
                value = wallets.sumOf { it.accountCount }.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryFact(
                label = "Last signed",
                value = lastSignedAtMs?.let { relativeTime(it, nowMs) } ?: "Never",
                modifier = Modifier.weight(1f),
            )
            SummaryFact(
                label = "Catalog",
                value = catalogDate ?: "Built in",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One fact of the Vault card: its small label, then its value in the mono face. */
@Composable
private fun SummaryFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        WalletEyebrow(label)
        Spacer(Modifier.height(LenslySpacing.xxs))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = LenslyOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The head of a section: its title, and the quiet link that opens the screen with all of it. */
@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        WalletEyebrow(title, modifier = Modifier.weight(1f))
        Text(
            text = action,
            modifier = Modifier.clickable(role = Role.Button, onClick = onAction),
            style = MaterialTheme.typography.labelMedium,
            color = LenslyOnSurface,
        )
    }
}

/**
 * The empty vault: the drawn lens, the invitation, and the two ways to start. Create is the accent action. A wallet can be
 * added any time from this state, and nowhere else on Home.
 */
@Composable
internal fun HomeEmptyState(onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VaultLens(modifier = Modifier.size(136.dp))
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "Create or restore your first wallet",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = LenslyOnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "Start with a new wallet of 24 words, or restore one you already have.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        PillButton(text = "Create wallet", onClick = onCreate, accent = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(LenslySpacing.sm))
        PillButton(text = "Restore wallet", onClick = onRestore, modifier = Modifier.fillMaxWidth())
    }
}

/** A circle of wallet art crossed by rings, with the accent at its centre. Drawn, so it needs no image. */
@Composable
private fun VaultLens(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(LenslySurfaceHigh),
    ) {
        WalletArt(seed = "lensly-vault", colour = ProfileColour.RED, modifier = Modifier.matchParentSize())
        Canvas(modifier = Modifier.matchParentSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            for (ring in 1..4) {
                drawCircle(
                    color = LenslyOnSurface.copy(alpha = 0.2f),
                    radius = radius * ring / 4f,
                    center = centre,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(color = LenslyAccent, radius = 6.dp.toPx(), center = centre)
        }
    }
}

/** A short summary of what the record did, such as "Sent 0.0100 MON on Monad Testnet". */
internal fun recordSummary(record: ReviewRecord): String {
    val network = record.networkName?.let { " on $it" }.orEmpty()
    return when {
        record.outcome == ReviewOutcome.REFUSED ->
            record.refusal?.let { "Refused: $it$network" } ?: "Refused$network"
        record.outcome == ReviewOutcome.CANCELLED -> "Cancelled a request$network"
        record.kind == ReviewKind.MESSAGE -> "Signed a message$network"
        record.amount != null -> "Sent ${record.amount}$network"
        record.function != null -> "${record.function}$network"
        else -> "Reviewed a request$network"
    }
}

/** How long before [nowMs] the record was made, in one short unit. */
internal fun relativeTime(atMs: Long, nowMs: Long): String {
    val elapsed = (nowMs - atMs).coerceAtLeast(0L)
    return when {
        elapsed < MINUTE_MS -> "Just now"
        elapsed < HOUR_MS -> "${elapsed / MINUTE_MS}m ago"
        elapsed < DAY_MS -> "${elapsed / HOUR_MS}h ago"
        elapsed < MONTH_MS -> "${elapsed / DAY_MS}d ago"
        else -> "${elapsed / MONTH_MS}mo ago"
    }
}

private fun countOf(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"

private fun outcomeIcon(outcome: ReviewOutcome): ImageVector = when (outcome) {
    ReviewOutcome.SIGNED -> Icons.Filled.Check
    ReviewOutcome.REFUSED -> Icons.Filled.Warning
    ReviewOutcome.CANCELLED -> Icons.Filled.Close
}

private fun outcomeTint(outcome: ReviewOutcome): Color = when (outcome) {
    ReviewOutcome.SIGNED -> LenslySuccess
    ReviewOutcome.REFUSED -> LenslyError
    ReviewOutcome.CANCELLED -> LenslyMuted
}

private fun outcomeLabel(outcome: ReviewOutcome): String = when (outcome) {
    ReviewOutcome.SIGNED -> "Signed"
    ReviewOutcome.REFUSED -> "Refused"
    ReviewOutcome.CANCELLED -> "Cancelled"
}
