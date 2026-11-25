package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.R
import app.lumio.data.config.AutoLock
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.LockSettings
import app.lumio.data.config.TransactionSettings
import app.lumio.ui.kit.ClearAirAlertNote
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirStopSlider
import app.lumio.ui.kit.ClearAirSwitch
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.home.LumioMark
import app.lumio.ui.wallets.ROW_DIVIDER_INDENT
import app.lumio.ui.wallets.RowDivider
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

/** The Display page: what Home shows. Each control applies as the owner changes it, and [notice] shows a change that the vault refused. */
@Composable
fun DisplaySettingsPage(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
    onCurrency: (FiatCurrency) -> Unit,
    onSmallBalance: (Double) -> Unit,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    SettingsPageScreen(SettingsPage.DISPLAY, offline, onBack, onLock) {
        DisplaySettingsSection(
            settings = settings,
            notice = notice,
            onShowTotal = onShowTotal,
            onShowAssets = onShowAssets,
            onCurrency = onCurrency,
            onSmallBalance = onSmallBalance,
        )
    }
}

/** The Networks page: the Esplora server of each Bitcoin network, then the Ethereum chains. Each part draws its own controls. */
@Composable
fun NetworksSettingsPage(
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    bitcoin: @Composable () -> Unit,
    ethereum: @Composable () -> Unit,
    backLabel: String = SETTINGS_BACK,
) {
    SettingsPageScreen(SettingsPage.NETWORKS, offline, onBack, onLock, backLabel = backLabel) {
        SettingsGroup(title = "Bitcoin") { bitcoin() }
        SettingsGroup(title = "Ethereum") { ethereum() }
    }
}

/** The Wallets page: each paired wallet, and the pairing of one more from Lensly. [wallets] draws them. */
@Composable
fun WalletsSettingsPage(offline: String?, onBack: () -> Unit, onLock: () -> Unit, wallets: @Composable () -> Unit) {
    SettingsPageScreen(SettingsPage.WALLETS, offline, onBack, onLock) { wallets() }
}

/** The Biometric Unlock switch of the Security page: whether it is [on], and whether the phone has a strong biometric to enable it. */
data class BiometricSwitch(val on: Boolean, val canEnable: Boolean)

/** Why Biometric Unlock cannot be turned on: the phone has no strong biometric enrolled. I9 says the same. */
const val BIOMETRIC_UNAVAILABLE_REASON = "Set up a fingerprint or face unlock on this phone first."

/**
 * The Security page: the App Password, whose row opens its change, Device Protection, whose switch turns Biometric Unlock off, or on after the App
 * Password step, the Hidden Entry Gesture, whose row opens the choice of another gesture, and Auto-Lock, whose row opens the choice of when Lumio locks
 * itself. Every change asks for the App Password first: [checkPassword] checks it, and [onAutoLock] applies a checked choice. The switch is unavailable
 * until the phone has a strong biometric enrolled, and its row says why.
 */
@Composable
fun SecuritySettingsPage(
    lock: LockSettings,
    biometric: BiometricSwitch,
    offline: String?,
    onChangePassword: () -> Unit,
    onBiometric: (Boolean) -> Unit,
    onEntryGesture: () -> Unit,
    checkPassword: suspend (CharArray) -> PasswordCheck,
    onAutoLock: (AutoLock) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val enabled = biometric.on || biometric.canEnable
    SettingsPageScreen(SettingsPage.SECURITY, offline, onBack, onLock) {
        ClearAirRow(
            title = "Password",
            description = "Change your Lumio password",
            leading = { SettingsDisc(SettingsGlyph.KEY) },
            onClick = onChangePassword,
        )
        RowDivider(indent = ROW_DIVIDER_INDENT)
        ClearAirRow(
            title = "Device Protection",
            description = if (enabled) "Fingerprint or face" else BIOMETRIC_UNAVAILABLE_REASON,
            leading = { SettingsDisc(SettingsGlyph.FINGERPRINT) },
            trailing = { ClearAirSwitch(checked = biometric.on, enabled = enabled) },
            modifier = Modifier.toggleable(value = biometric.on, enabled = enabled, role = Role.Switch, onValueChange = onBiometric),
        )
        RowDivider(indent = ROW_DIVIDER_INDENT)
        ClearAirRow(
            title = "Hidden Entry Gesture",
            description = "How you open Lumio",
            leading = { SettingsDisc(SettingsGlyph.HAND) },
            onClick = onEntryGesture,
        )
        RowDivider(indent = ROW_DIVIDER_INDENT)
        AutoLockRow(current = lock.autoLock, checkPassword = checkPassword, onChoose = onAutoLock)
        SettingsInfoNote(text = "Each change asks for your current password first.", modifier = Modifier.padding(top = ClearAirSpacing.sm))
    }
}

/**
 * The About page: the mark with the name of the app and its version, the warning that AirVault has no token, no sale and no support team, the licences of
 * what the app ships, and the sources of its data.
 */
@Composable
fun AboutSettingsPage(version: String, offline: String?, onLicences: () -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    SettingsFrame(title = "About Lumio", offline = offline, onBack = onBack, onLock = onLock, backLabel = SETTINGS_BACK) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LumioMark(size = 72.dp)
            Text(text = "Lumio", modifier = Modifier.padding(top = ClearAirSpacing.sm), style = ClearAirType.titleL, color = palette.ink)
            Text(text = "The AirVault companion app", style = ClearAirType.body, color = palette.ink2)
            Text(text = "Version $version", modifier = Modifier.padding(top = 6.dp), style = ClearAirType.address, color = palette.ink)
        }
        ClearAirAlertNote(
            text = "AirVault has no token, no sale and no support team. Anyone who says otherwise is trying to take your money.",
            modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter).padding(top = ClearAirSpacing.lg),
        )
        ClearAirRow(
            title = stringResource(R.string.settings_licences_open),
            description = stringResource(R.string.settings_licences_body),
            leading = { SettingsDisc(SettingsGlyph.DOC) },
            onClick = onLicences,
            modifier = Modifier.padding(top = 10.dp),
        )
        SettingsGroup(title = stringResource(R.string.settings_sources_heading)) {
            ClearAirRow(title = stringResource(R.string.settings_sources_bitcoin_title), description = stringResource(R.string.settings_sources_bitcoin_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_electrum_title), description = stringResource(R.string.settings_sources_electrum_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_explorers_title), description = stringResource(R.string.settings_sources_explorers_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_rpc_title), description = stringResource(R.string.settings_sources_rpc_body))
            ClearAirRow(title = stringResource(R.string.settings_sources_prices_title), description = stringResource(R.string.settings_sources_prices_body))
            ClearAirNote(text = stringResource(R.string.settings_sources_thanks), modifier = Modifier.padding(top = ClearAirSpacing.xs))
        }
    }
}

/**
 * The Transactions page: whether spam is hidden from Activity, the Last Chance pause before a send is broadcast, and the backup of the transaction
 * comments, which [comments] draws. Each control applies as the owner changes it, and [notice] shows a change that the vault refused.
 */
@Composable
fun TransactionsSettingsPage(
    settings: TransactionSettings,
    notice: String?,
    onHideSpam: (Boolean) -> Unit,
    onLastChance: (Int) -> Unit,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    comments: @Composable () -> Unit,
) {
    SettingsPageScreen(SettingsPage.TRANSACTIONS, offline, onBack, onLock) {
        notice?.let {
            ClearAirAlertNote(text = it, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
        }
        ClearAirSwitchRow(
            title = "Hide Spam",
            description = "Keeps spam and scam transfers out of Activity. You can still show them there.",
            checked = settings.hideSpam,
            onCheckedChange = onHideSpam,
        )
        val choices = TransactionSettings.LAST_CHANCE_CHOICES
        SettingsGroup(title = "Last Chance before Sending · ${settings.lastChanceSeconds} s") {
            ClearAirStopSlider(
                // Only the ends are labelled. The title above carries the value, and the track has a stop for each choice.
                labels = choices.map { if (it == choices.first() || it == choices.last()) "$it s" else "" },
                selected = choices.indexOf(settings.lastChanceSeconds).coerceAtLeast(0),
                onSelect = { index -> onLastChance(choices[index]) },
                modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
            )
            ClearAirNote(
                text = "After Lensly signs, Lumio waits this long before sending, so you can still stop it. At 0 s it sends at once.",
                modifier = Modifier.padding(top = ClearAirSpacing.xs),
            )
        }
        comments()
    }
}

/** The back label of a settings page opened from Settings: the list it returns to. A page opened from elsewhere names the screen it returns to. */
internal const val SETTINGS_BACK = "Settings"
