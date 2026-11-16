package app.lumio.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.components.SettingsSection
import app.lensly.ui.components.SettingsSwitchRow
import app.lensly.ui.components.SliderSetting
import app.lensly.ui.theme.LenslySpacing
import app.lumio.R
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.LockSettings

/** The Display page: what Home shows. Each control applies as the owner changes it, and [notice] shows a change that the vault refused. */
@Composable
fun DisplaySettingsPage(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
    onMinimumUsd: (Double) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScreen(SettingsPage.DISPLAY, onBack) {
        DisplaySettingsSection(
            settings = settings,
            notice = notice,
            onShowTotal = onShowTotal,
            onShowAssets = onShowAssets,
            onMinimumUsd = onMinimumUsd,
        )
    }
}

/** The Transactions page: the spam filter, then the comment backup, which [comments] draws. [notice] shows a change that the vault refused. */
@Composable
fun TransactionsSettingsPage(
    hideSpam: Boolean,
    notice: String?,
    onHideSpam: (Boolean) -> Unit,
    onBack: () -> Unit,
    comments: @Composable () -> Unit,
) {
    SettingsPageScreen(SettingsPage.TRANSACTIONS, onBack) {
        notice?.let {
            InlineBanner(message = it, tone = BannerTone.Error)
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        SettingsSwitchRow(
            title = "Hide spam and scam transactions",
            description = "Hides transactions that look like spam or scams.",
            checked = hideSpam,
            onCheckedChange = onHideSpam,
        )
        SettingsSection(title = "Comments") { comments() }
    }
}

/** The Networks page: the Esplora server of each Bitcoin network, then the Ethereum chains. Each part draws its own controls. */
@Composable
fun NetworksSettingsPage(onBack: () -> Unit, bitcoin: @Composable () -> Unit, ethereum: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.NETWORKS, onBack) {
        SettingsSection(title = "Bitcoin") { bitcoin() }
        SettingsSection(title = "Ethereum") { ethereum() }
    }
}

/** The Wallets page: the pairing of a wallet from Lensly, and each paired wallet with its delete row. [wallets] draws them. */
@Composable
fun WalletsSettingsPage(onBack: () -> Unit, wallets: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.WALLETS, onBack) { wallets() }
}

/** The Address book page: each paired wallet's own address book, which opens read only. [wallets] draws the rows. */
@Composable
fun AddressBookSettingsPage(onBack: () -> Unit, wallets: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.ADDRESS_BOOK, onBack) {
        SettingsSection(title = "Wallets") { wallets() }
    }
}

/**
 * The Security page: the Hidden Entry Gesture, whose row opens the choice of another gesture (it asks for the App Password first), the
 * App Password, whose row opens its change, and the lock: the inactivity timeout, and the switch that locks when the app is switched away
 * from. Each control applies as the owner changes it.
 */
@Composable
fun SecuritySettingsPage(
    entryGesture: EntryGesture,
    lock: LockSettings,
    onEntryGesture: () -> Unit,
    onChangePassword: () -> Unit,
    onIdleMinutes: (Int) -> Unit,
    onLockWhenSwitching: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScreen(SettingsPage.SECURITY, onBack) {
        SettingsSection(title = "Entry") {
            SettingsRow(title = "Hidden entry gesture", description = entryGesture.title, onClick = onEntryGesture)
            Spacer(Modifier.height(LenslySpacing.sm))
            Text(
                "Choosing another gesture asks for the App Password first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection(title = "Password") {
            SettingsRow(
                title = "Change password",
                description = "Opens Lumio. Lensly's password is separate.",
                onClick = onChangePassword,
            )
        }
        SettingsSection(title = "Lock") {
            SliderSetting(
                title = "Lock after inactivity",
                options = LockSettings.IDLE_CHOICES,
                selected = lock.idleMinutes,
                label = ::idleLabel,
                onSelect = onIdleMinutes,
                caption = { minutes -> if (minutes == LockSettings.DEFAULT_IDLE_MINUTES) "Default" else null },
            )
            Text(
                "Lumio locks after this long without a touch, in the background too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            SettingsSwitchRow(
                title = "Lock when switching apps",
                description = "Lumio locks as soon as you leave it. Off, it stays unlocked until the inactivity lock.",
                checked = lock.lockWhenSwitchingApps,
                onCheckedChange = onLockWhenSwitching,
            )
        }
    }
}

/** A choice of the idle lock as the owner reads it: "1 minute", "30 minutes", "1 hour". */
private fun idleLabel(minutes: Int): String = when (minutes) {
    1 -> "1 minute"
    60 -> "1 hour"
    else -> "$minutes minutes"
}

/** The About page: the version of the app, and the licences of what it ships. */
@Composable
fun AboutSettingsPage(version: String, onLicences: () -> Unit, onBack: () -> Unit) {
    SettingsPageScreen(SettingsPage.ABOUT, onBack) {
        SettingsRow(title = "Version", description = version)
        SettingsSection(title = "Licences") {
            SettingsRow(
                title = stringResource(R.string.settings_licences_open),
                description = stringResource(R.string.settings_licences_body),
                onClick = onLicences,
            )
        }
    }
}

/** The Developer page, which only a debug build lists: [section] is the switch of the debug-only demo portfolio. */
@Composable
fun DeveloperSettingsPage(onBack: () -> Unit, section: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.DEVELOPER, onBack) {
        SettingsSection(title = "Demo") { section() }
    }
}
