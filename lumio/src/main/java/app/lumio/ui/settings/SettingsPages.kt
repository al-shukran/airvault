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
    onBack: () -> Unit,
) {
    SettingsPageScreen(SettingsPage.DISPLAY, onBack) {
        DisplaySettingsSection(
            settings = settings,
            notice = notice,
            onShowTotal = onShowTotal,
            onShowAssets = onShowAssets,
        )
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

/** The Address book page: each paired wallet's own address book. [wallets] draws the rows. */
@Composable
fun AddressBookSettingsPage(onBack: () -> Unit, wallets: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.ADDRESS_BOOK, onBack) {
        SettingsSection(title = "Wallets") { wallets() }
    }
}

/** The Biometric Unlock switch of the Security page: whether it is [on], and whether the phone has a strong biometric to enable it. */
data class BiometricSwitch(val on: Boolean, val canEnable: Boolean)

/** Why Biometric Unlock cannot be turned on: the phone has no strong biometric enrolled. I9 says the same. */
const val BIOMETRIC_UNAVAILABLE_REASON = "Set up a fingerprint or face unlock on this phone first."

/**
 * The Security page: the Hidden Entry Gesture, whose row opens the choice of another gesture (it asks for the App Password first), the
 * App Password, whose row opens its change, Biometric Unlock when [biometric] is given, and the lock: the inactivity timeout, and the
 * switch that locks when the app is switched away from. Each control applies as the owner changes it.
 */
@Composable
fun SecuritySettingsPage(
    entryGesture: EntryGesture,
    lock: LockSettings,
    biometric: BiometricSwitch?,
    onEntryGesture: () -> Unit,
    onChangePassword: () -> Unit,
    onBiometric: (Boolean) -> Unit,
    onIdleMinutes: (Int) -> Unit,
    onLockWhenSwitching: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScreen(SettingsPage.SECURITY, onBack) {
        SettingsSection(title = "Entry") {
            SettingsRow(title = "Hidden Entry Gesture", description = entryGesture.title, onClick = onEntryGesture)
            Spacer(Modifier.height(LenslySpacing.sm))
            Text(
                "Choosing another gesture asks for the App Password first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection(title = "Password") {
            SettingsRow(
                title = "Change Password",
                description = "Opens Lumio. Lensly's password is separate.",
                onClick = onChangePassword,
            )
        }
        if (biometric != null) {
            SettingsSection(title = "Unlock") {
                SettingsSwitchRow(
                    title = "Biometric Unlock",
                    description = if (biometric.on || biometric.canEnable) {
                        "A fingerprint or face opens Lumio. Your password still works."
                    } else {
                        BIOMETRIC_UNAVAILABLE_REASON
                    },
                    checked = biometric.on,
                    onCheckedChange = onBiometric,
                    enabled = biometric.on || biometric.canEnable,
                )
            }
        }
        SettingsSection(title = "Lock") {
            SliderSetting(
                title = "Lock after Inactivity",
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
                title = "Lock When Switching Apps",
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

/** The Transactions page: the backup of the transaction comments, which [comments] draws. */
@Composable
fun TransactionsSettingsPage(onBack: () -> Unit, comments: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.TRANSACTIONS, onBack) {
        comments()
    }
}
