package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
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
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslyWarning
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

/**
 * A wallet card in the carousel. Two cards fit the screen with the next card's edge showing, and the text of that next card starts past the
 * screen's edge, so no name is cut by it.
 */
private val CAROUSEL_CARD_WIDTH = 182.dp

/** The height of a readiness tile. Two rows of them make the grid. */
private val TILE_HEIGHT = 96.dp

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
        SectionHeader(title = "Recent activity") {
            SectionLink(text = "See all", onClick = onSeeAll)
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        if (records != null) {
            if (records.isEmpty()) {
                EmptyLine(text = "Nothing signed yet")
            } else {
                records.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = LenslyOutline)
                    ActivityRow(item = item, walletName = walletNames[item.walletId], nowMs = nowMs)
                }
            }
        }
    }
}

/**
 * WALLETS: every wallet of the session as a card, in a row that runs to both edges of the screen. The first card starts at the page
 * gutter, and the rest scroll in from the right. A tap opens a wallet, and a long press opens its context sheet, as on the Wallets
 * list. [onAllWallets] opens the full list.
 */
@Composable
internal fun WalletCarouselSection(
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    onOpen: (String) -> Unit,
    onLongPress: (WalletMeta) -> Unit,
    onAllWallets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Wallets", modifier = Modifier.padding(horizontal = WALLET_GUTTER)) {
            SectionLink(text = "All wallets", onClick = onAllWallets)
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag(HOME_CAROUSEL_TAG),
            contentPadding = PaddingValues(horizontal = WALLET_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            itemsIndexed(items = wallets, key = { _, wallet -> wallet.id }) { index, wallet ->
                WalletCard(
                    wallet = wallet,
                    profile = profiles[wallet.id] ?: WalletProfile(),
                    position = index,
                    onClick = { onOpen(wallet.id) },
                    onLongPress = { onLongPress(wallet) },
                    compact = true,
                    // The fingerprint is left to the Wallets list: a card this narrow would only cut it short.
                    showFingerprint = false,
                    // Two lines hold a name of up to about forty characters.
                    nameLines = 2,
                    modifier = Modifier.width(CAROUSEL_CARD_WIDTH),
                )
            }
        }
    }
}

/**
 * READINESS: the networks and the tokens the catalog holds, the installed catalog's version, and the lockout cap, as four equal
 * tiles in a two-by-two grid. Each tile has a status dot, and a tap opens its Settings screen.
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
        Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                ReadinessTile(
                    label = "Networks",
                    value = readiness.networkCount.toString(),
                    status = countStatus(readiness.networkCount),
                    tag = READINESS_NETWORKS_TAG,
                    onClick = onNetworks,
                    modifier = Modifier.weight(1f),
                )
                ReadinessTile(
                    label = "Tokens",
                    value = readiness.tokenCount.toString(),
                    status = countStatus(readiness.tokenCount),
                    tag = READINESS_TOKENS_TAG,
                    onClick = onTokens,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                ReadinessTile(
                    label = "Catalog",
                    value = readiness.catalogSequence?.let { "Sequence $it" } ?: "Built-in",
                    status = LenslySuccess,
                    tag = READINESS_CATALOG_TAG,
                    onClick = onCatalog,
                    modifier = Modifier.weight(1f),
                )
                ReadinessTile(
                    label = "Lockout",
                    value = readiness.lockout.label,
                    status = lockoutStatus(readiness.lockout),
                    tag = READINESS_LOCKOUT_TAG,
                    onClick = onLockout,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
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
            color = LenslyOnSurface,
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
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelMedium, color = LenslyOnSurface)
        Spacer(Modifier.width(LenslySpacing.xxs))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** One row of Recent activity: the outcome mark, the summary, and the wallet and time in the caps line under it. */
@Composable
private fun ActivityRow(item: WalletReviewRecord, walletName: String?, nowMs: Long) {
    val record = item.record
    Row(
        modifier = Modifier.fillMaxWidth().testTag(HOME_ACTIVITY_ROW_TAG).padding(vertical = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutcomeMark(outcome = record.outcome)
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activitySummary(record),
                style = MaterialTheme.typography.bodyMedium,
                color = LenslyOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
            Row {
                // The name gives way first, so the time is always whole.
                Text(
                    text = (walletName ?: "Wallet").uppercase(),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = " · ${relativeAge(record.atMs, nowMs)}".uppercase(),
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
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyOutline, CircleShape),
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

/**
 * One readiness tile: its label in the caps line with a status dot, and its value in the mono face under it. The whole tile is the
 * tap target, and it dims while pressed and plays the key haptic.
 */
@Composable
private fun ReadinessTile(
    label: String,
    value: String,
    status: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Column(
        modifier = modifier
            .height(TILE_HEIGHT)
            .testTag(tag)
            .pressFeedback(interaction, enabled = true)
            .clip(shape)
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyOutline, shape)
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(LenslySpacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(status))
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = LenslyOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A count is ready when the catalog holds any of it. */
private fun countStatus(count: Int): Color = if (count > 0) LenslySuccess else LenslyWarning

/** A lockout under [SHORT_LOCKOUT_MS] warns. The default and the longer caps are ready. */
private fun lockoutStatus(choice: FailureCapChoice): Color {
    val cap = choice.capMs
    return if (cap != null && cap < SHORT_LOCKOUT_MS) LenslyWarning else LenslySuccess
}

/** One line in a card, for a list with nothing in it yet. */
@Composable
private fun EmptyLine(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LenslyRadius.lg))
            .background(LenslySurfaceContainer)
            .padding(LenslySpacing.md),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
