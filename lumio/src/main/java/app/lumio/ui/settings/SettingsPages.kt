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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.R
import app.lumio.data.config.AutoLock
import app.lumio.data.config.LockSettings
import app.lumio.data.config.TransactionSettings
import app.lumio.ui.home.LumioMark
import app.lumio.ui.kit.ClearAirAlertNote
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirGlyphDisc
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirStopSlider
import app.lumio.ui.kit.ClearAirSwitch
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.kit.LocalPageScroll
import app.lumio.ui.kit.PageScroll
import app.lumio.ui.shell.LumioPageScreen
import app.lumio.ui.wallets.ROW_DIVIDER_INDENT
import app.lumio.ui.wallets.RowDivider

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
    sheet: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    LumioPageScreen(title = title, backLabel = backLabel, offline = offline, onBack = onBack, onLock = onLock, sheet = sheet) {
        val scroll = rememberScrollState()
        val page = remember(scroll) { PageScroll(scroll) }
        CompositionLocalProvider(LocalPageScroll provides page) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { page.viewport = it.boundsInWindow() }
                    .verticalScroll(page.state),
                content = content,
            )
        }
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
    sheet: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsFrame(title = page.title, offline = offline, onBack = onBack, onLock = onLock, backLabel = backLabel, sheet = sheet, content = content)
}

/** A group of a settings page: its eyebrow, then its rows. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.lg)) {
        ClearAirEyebrow(text = title, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
        content()
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
            leading = { ClearAirGlyphDisc(ClearAirGlyph.KEY, size = 19.dp) },
            onClick = onChangePassword,
        )
        RowDivider(indent = ROW_DIVIDER_INDENT)
        ClearAirRow(
            title = "Device Protection",
            description = if (enabled) "Fingerprint or face" else BIOMETRIC_UNAVAILABLE_REASON,
            leading = { ClearAirGlyphDisc(ClearAirGlyph.FINGERPRINT, size = 19.dp) },
            trailing = { ClearAirSwitch(checked = biometric.on, enabled = enabled) },
            modifier = Modifier.toggleable(value = biometric.on, enabled = enabled, role = Role.Switch, onValueChange = onBiometric),
        )
        RowDivider(indent = ROW_DIVIDER_INDENT)
        ClearAirRow(
            title = "Hidden Entry Gesture",
            description = "How you open Lumio",
            leading = { ClearAirGlyphDisc(ClearAirGlyph.HAND, size = 19.dp) },
            onClick = onEntryGesture,
        )
        RowDivider(indent = ROW_DIVIDER_INDENT)
        AutoLockRow(current = lock.autoLock, checkPassword = checkPassword, onChoose = onAutoLock)
        ClearAirNote(text = "Each change asks for your current password first.", modifier = Modifier.padding(top = ClearAirSpacing.sm))
    }
}

/** The glyph on the disc of a Security row. */
@Composable
private fun SecurityGlyph(glyph: ClearAirGlyph) = ClearAirGlyphDisc(glyph, size = 19.dp)

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
            leading = { ClearAirGlyphDisc(ClearAirGlyph.DOC, size = 19.dp) },
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

/** The back label of a settings page opened from Settings: the list it returns to. A page opened from elsewhere names the screen it returns to. */
internal const val SETTINGS_BACK = "Settings"
