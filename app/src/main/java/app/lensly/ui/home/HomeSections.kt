package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.app.sign.ReviewOutcome
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.LumioRow
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalLumenPalette
import app.lensly.ui.wallet.WALLET_GUTTER

/** The test tags of the Home sections, the carousel and the readiness tiles. */
internal const val HOME_CAROUSEL_TAG = "home-carousel"
internal const val HOME_ACTIVITY_ROW_TAG = "home-activity-row"
internal const val READINESS_NETWORKS_TAG = "readiness-networks"
internal const val READINESS_TOKENS_TAG = "readiness-tokens"
internal const val READINESS_CATALOG_TAG = "readiness-catalog"
internal const val READINESS_LOCKOUT_TAG = "readiness-lockout"

/** How many records Recent activity shows: the newest ones. */
internal const val HOME_ACTIVITY_LIMIT = 3

/** A wrong-password wait below this is a short one, so its tile carries a warning dot. */
private const val SHORT_LOCKOUT_MS = 10_000L

/**
 * What the readiness tiles show: how many networks and tokens the catalog holds, the sequence of the installed catalog package
 * (null while the built-in catalog is all there is), and the lockout cap in force.
 */
data class HomeReadiness(
    val networkCount: Int,
    val tokenCount: Int,
    val catalogSequence: Long?,
    val lockout: FailureCapChoice,
)

/**
 * RECENT ACTIVITY: the records in [records], already the newest ones, each with its outcome, a summary of at most two lines, the
 * wallet it belongs to and when it was made. [walletNames] names each wallet by id. With no record it says nothing has been signed.
 * [records] is null until the read finishes, and then the section shows only its heading. [onSeeAll] opens the full Activity.
 */
@Composable
internal fun RecentActivitySection(
    records: List<WalletReviewRecord>?,
    walletNames: Map<String, String>,
    nowMs: Long,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER)) {
        SectionHeader(title = "Recent Activity") {
            SectionLink(text = "See All", onClick = onSeeAll)
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        if (records != null) {
            LumioIsland {
                if (records.isEmpty()) {
                    EmptyLine(text = "Nothing signed yet")
                } else {
                    records.forEachIndexed { index, item ->
                        if (index > 0) IslandDivider()
                        ActivityRow(item = item, walletName = walletNames[item.walletId], nowMs = nowMs)
                    }
                }
            }
        }
    }
}

/**
 * WALLETS: every wallet of the session as a row of one island, in the order of the Wallets list. A tap opens a wallet, and a long press
 * opens its context sheet, as on the Wallets list. [onAllWallets] opens the full list.
 */
@Composable
internal fun WalletListSection(
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    accounts: Map<String, Int>,
    onOpen: (String) -> Unit,
    onLongPress: (WalletMeta) -> Unit,
    onAllWallets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Wallets", modifier = Modifier.padding(horizontal = WALLET_GUTTER)) {
            SectionLink(text = "All Wallets", onClick = onAllWallets)
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        LumioIsland(modifier = Modifier.testTag(HOME_CAROUSEL_TAG).padding(horizontal = WALLET_GUTTER)) {
            wallets.forEachIndexed { index, wallet ->
                if (index > 0) IslandDivider()
                WalletRow(
                    wallet = wallet,
                    profile = profiles[wallet.id] ?: WalletProfile(),
                    accounts = accounts[wallet.id] ?: 0,
                    position = index,
                    onClick = { onOpen(wallet.id) },
                    onLongPress = { onLongPress(wallet) },
                    showFingerprint = false,
                )
            }
        }
    }
}

/**
 * READINESS: the networks and the tokens the catalog holds, the installed catalog's version, and the lockout cap, as the four rows of
 * one island. Each row has a status dot after its value, and a tap opens its Settings screen.
 */
@Composable
internal fun ReadinessSection(
    readiness: HomeReadiness,
    onNetworks: () -> Unit,
    onTokens: () -> Unit,
    onCatalog: () -> Unit,
    onLockout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER)) {
        SectionHeader(title = "Readiness")
        Spacer(Modifier.height(LenslySpacing.xs))
        LumioIsland {
            ReadinessRow(
                label = "Networks",
                value = readiness.networkCount.toString(),
                status = countStatus(readiness.networkCount),
                tag = READINESS_NETWORKS_TAG,
                onClick = onNetworks,
            )
            IslandDivider()
            ReadinessRow(
                label = "Tokens",
                value = readiness.tokenCount.toString(),
                status = countStatus(readiness.tokenCount),
                tag = READINESS_TOKENS_TAG,
                onClick = onTokens,
            )
            IslandDivider()
            ReadinessRow(
                label = "Catalog",
                value = readiness.catalogSequence?.let { "Sequence $it" } ?: "Built-in",
                status = LocalLumenPalette.current.positive,
                tag = READINESS_CATALOG_TAG,
                onClick = onCatalog,
            )
            IslandDivider()
            ReadinessRow(
                label = "Lockout",
                // The default cap reads as Default here, as it always has, so a vault that never changed it sees no change.
                value = if (readiness.lockout == FailureCapChoice.DEFAULT) "Default" else readiness.lockout.label,
                status = lockoutStatus(readiness.lockout),
                tag = READINESS_LOCKOUT_TAG,
                onClick = onLockout,
            )
        }
    }
}

/** One readiness row: its label, then its value and status dot at the end. The whole row opens its Settings screen. */
@Composable
private fun ReadinessRow(label: String, value: String, status: Color, tag: String, onClick: () -> Unit) {
    LumioRow(
        title = label,
        modifier = Modifier.testTag(tag),
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(LenslySpacing.xs))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(status))
        },
        onClick = onClick,
    )
}

/** A section's heading: its title in the mono caps style, with an optional action at the end of the same row. */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(LenslySpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

/** The link at the end of a heading: mono caps and an arrow. A tap runs [onClick] with the key haptic. */
@Composable
private fun SectionLink(text: String, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = Modifier
            .padding(vertical = LenslySpacing.xs)
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(LenslySpacing.xxs))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** One row of Recent activity: the outcome mark, the summary, and the wallet and time in the caps line under it. */
@Composable
private fun ActivityRow(item: WalletReviewRecord, walletName: String?, nowMs: Long) {
    val record = item.record
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HOME_ACTIVITY_ROW_TAG)
            .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutcomeMark(outcome = record.outcome)
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activitySummary(record),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
            Row {
                // The name gives way first, so the time is always whole.
                Text(
                    text = walletName ?: "Wallet",
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = " · ${relativeAge(record.atMs, nowMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** The outcome as a small round mark, with its icon in the outcome's colour. */
@Composable
private fun OutcomeMark(outcome: ReviewOutcome) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = outcomeIcon(outcome),
            contentDescription = outcomeLabel(outcome),
            tint = outcomeTint(outcome),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A count is ready when the catalog holds any of it. */
@Composable
private fun countStatus(count: Int): Color = if (count > 0) LocalLumenPalette.current.positive else LocalLumenPalette.current.warning

/** A lockout under [SHORT_LOCKOUT_MS] warns. The default and the longer caps are ready. */
@Composable
private fun lockoutStatus(choice: FailureCapChoice): Color {
    val cap = choice.capMs
    return if (cap != null && cap < SHORT_LOCKOUT_MS) LocalLumenPalette.current.warning else LocalLumenPalette.current.positive
}

/** One line inside an island, for a list with nothing in it yet. */
@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
