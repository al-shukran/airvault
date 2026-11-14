package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletMeta
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslyWarning

/** The test tag of the backup panel, so a test can find it. */
internal const val HOME_BACKUP_TAG = "home-backup"

/** The wallets whose seed phrase is not backed up yet, in list order. Home shows the backup panel while this is not empty. */
internal fun walletsNeedingBackup(wallets: List<WalletMeta>): List<WalletMeta> = wallets.filterNot { it.seedBackedUp }

/**
 * The backup panel, shown while a wallet's seed phrase is not backed up. It has a warm edge, a shield, one line on why, and one action:
 * [onBackup] opens the Show Secret flow for the first of [wallets]. It has no dismiss, so it stays until the seed is backed up. With more
 * than one wallet to back up, the title counts them. [wallets] is never empty.
 */
@Composable
internal fun BackupPanel(
    wallets: List<WalletMeta>,
    onBackup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HOME_BACKUP_TAG)
            .clip(shape)
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyWarning.copy(alpha = 0.6f), shape)
            .padding(LenslySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(LenslyWarning.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LenslyIcons.Shield,
                contentDescription = null,
                tint = LenslyWarning,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (wallets.size == 1) "Back up 24 words" else "Back up ${wallets.size} wallets",
                style = MaterialTheme.typography.titleSmall,
                color = LenslyOnSurface,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
            Text(
                // A no-break space keeps "24 words" on one line.
                text = "If this phone is lost, the 24 words are the only way back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        PillButton(text = "Back up", onClick = { onBackup(wallets.first().id) })
    }
}
