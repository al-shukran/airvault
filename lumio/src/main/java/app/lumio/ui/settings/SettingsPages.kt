package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.R
import app.lumio.data.config.AutoLock
import app.lumio.data.config.LockSettings
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.shell.LumioPageScreen

/**
 * The frame of a settings screen: a Clear Air page under [title], with its back arrow to the list, the offline band when [offline] is set, and the lock.
 * The page's controls apply as the owner changes them. The content scrolls as one column.
 */
@Composable
fun SettingsFrame(
    title: String,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    backLabel: String = SETTINGS_BACK,
    content: @Composable ColumnScope.() -> Unit,
) {
    LumioPageScreen(title = title, backLabel = backLabel, offline = offline, onBack = onBack, onLock = onLock) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
    }
}

/** The frame of one settings page: [SettingsFrame] under the page's own title, and its back arrow to [backLabel]. */
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    backLabel: String = SETTINGS_BACK,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsFrame(title = page.title, offline = offline, onBack = onBack, onLock = onLock, backLabel = backLabel, content = content)
}

/** A group of a settings page: its eyebrow, then its rows. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.lg)) {
        ClearAirEyebrow(text = title, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
        content()
    }
}


/** The Wallets page: the pairing of a wallet from Lensly, and each paired wallet with its delete row. [wallets] draws them. */
@Composable
fun WalletsSettingsPage(offline: String?, onBack: () -> Unit, onLock: () -> Unit, wallets: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.WALLETS, offline, onBack, onLock) { wallets() }
}

/** The Address book page: each paired wallet's own address book. [wallets] draws the rows. */
@Composable
fun AddressBookSettingsPage(offline: String?, onBack: () -> Unit, onLock: () -> Unit, wallets: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.ADDRESS_BOOK, offline, onBack, onLock) {
        SettingsGroup(title = "Wallets") { wallets() }
    }
}

/** The Biometric Unlock switch of the Device Protection page: whether it is [on], and whether the phone has a strong biometric to enable it. */
data class BiometricSwitch(val on: Boolean, val canEnable: Boolean)

/** Why Biometric Unlock cannot be turned on: the phone has no strong biometric enrolled. I9 says the same. */
const val BIOMETRIC_UNAVAILABLE_REASON = "Set up a fingerprint or face unlock on this phone first."

/**
 * The Security page: the Hidden Entry Gesture, whose row opens the choice of another gesture (it asks for the App Password first), the
 * App Password, whose row opens its change, Device Protection, whose row says whether it is on, and Auto-Lock, whose row opens the choice of
 * when Lumio locks itself. Every change asks for the App Password first: [checkPassword] checks it, and [onAutoLock] applies a checked choice.
 */
@Composable
fun SecuritySettingsPage(
    entryGesture: EntryGesture,
    lock: LockSettings,
    deviceProtectionOn: Boolean,
    offline: String?,
    onEntryGesture: () -> Unit,
    onChangePassword: () -> Unit,
    onDeviceProtection: () -> Unit,
    checkPassword: suspend (CharArray) -> PasswordCheck,
    onAutoLock: (AutoLock) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    SettingsPageScreen(SettingsPage.SECURITY, offline, onBack, onLock) {
        SettingsGroup(title = "Entry") {
            ClearAirRow(title = "Hidden Entry Gesture", description = entryGesture.title, onClick = onEntryGesture)
            ClearAirNote(text = "Choosing another gesture asks for the App Password first.", modifier = Modifier.padding(top = ClearAirSpacing.xs))
        }
        SettingsGroup(title = "Password") {
            ClearAirRow(title = "Change Password", description = "Change your Lumio password", onClick = onChangePassword)
        }
        SettingsGroup(title = "Unlock") {
            ClearAirRow(title = "Device Protection", description = if (deviceProtectionOn) "On" else "Off", onClick = onDeviceProtection)
        }
        SettingsGroup(title = "Lock") {
            AutoLockRow(current = lock.autoLock, checkPassword = checkPassword, onChoose = onAutoLock)
            ClearAirNote(text = "Each change asks for your current password first.", modifier = Modifier.padding(top = ClearAirSpacing.xs))
        }
    }
}

/**
 * The Device Protection page: the Biometric Unlock switch. Turning it on asks for the App Password first, on the step that opens from here.
 * The switch is unavailable until the phone has a strong biometric enrolled, and the page says why.
 */
@Composable
fun DeviceProtectionSettingsPage(biometric: BiometricSwitch, offline: String?, onBiometric: (Boolean) -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    SettingsFrame(title = "Device Protection", offline = offline, onBack = onBack, onLock = onLock) {
        SettingsGroup(title = "Unlock") {
            ClearAirSwitchRow(
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
}

/** The About page: the version of the app, and the licences of what it ships. */
@Composable
fun AboutSettingsPage(version: String, offline: String?, onLicences: () -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    SettingsPageScreen(SettingsPage.ABOUT, offline, onBack, onLock) {
        ClearAirRow(title = "Version", description = version)
        ClearAirNote(text = "AirVault has no token, no sale and no support team.", modifier = Modifier.padding(top = ClearAirSpacing.xs))
        SettingsGroup(title = stringResource(R.string.settings_sources_heading)) {
            ClearAirRow(title = stringResource(R.string.settings_sources_bitcoin_title), description = stringResource(R.string.settings_sources_bitcoin_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_electrum_title), description = stringResource(R.string.settings_sources_electrum_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_explorers_title), description = stringResource(R.string.settings_sources_explorers_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_rpc_title), description = stringResource(R.string.settings_sources_rpc_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_prices_title), description = stringResource(R.string.settings_sources_prices_body))
            ClearAirNote(text = stringResource(R.string.settings_sources_thanks), modifier = Modifier.padding(top = ClearAirSpacing.xs))
        }
        SettingsGroup(title = "Licences") {
            ClearAirRow(
                title = stringResource(R.string.settings_licences_open),
                description = stringResource(R.string.settings_licences_body),
                onClick = onLicences,
            )
        }
    }
}

/** The back label of a settings page opened from Settings: the list it returns to. A page opened from elsewhere names the screen it returns to. */
internal const val SETTINGS_BACK = "Settings"
