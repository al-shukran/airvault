package app.lensly.ui.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.lensly.app.export.AccountExportView
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.home.groupedAddress
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalMonoFamily
import app.lensly.ui.wallet.PasswordStep
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import kotlinx.coroutines.launch

private const val PUBLIC_LABEL = "Public — safe to share"
private const val EXPORT_PASSWORD_PROMPT = "Enter your Application Password to show the export."

/** The title of an export of [count] accounts: one account, or several. */
private fun exportTitle(count: Int): String = if (count == 1) "Export Account" else "Export $count Accounts"

/**
 * EXPORT of one account, or of a selection of accounts as one sequence. Each account is one step: its code, animated when its key
 * takes more than one frame, with its address and derivation path under it. With several accounts, "Account 2 of 5" sits above the
 * code and NEXT moves on, and DONE follows the last. CODE and TEXT switch between that code and a plain-text view of every field, in
 * full. Public data only, labelled as safe to share.
 */
@Composable
internal fun AccountExportScreen(
    accounts: List<AccountExportView>,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var showText by rememberSaveable { mutableStateOf(false) }
    val last = accounts.lastIndex
    val current = accounts[step.coerceIn(0, last)]
    LenslyScreen(
        title = exportTitle(accounts.size),
        modifier = modifier,
        onBack = onBack,
        scrollable = false,
        bottomAction = {
            PillButton(
                text = if (step >= last) "Done" else "Next",
                onClick = { if (step >= last) onDone() else step++ },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AddressIcon(size = 16.dp)
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(
                text = PUBLIC_LABEL,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (accounts.size > 1) WalletEyebrow("Account ${step + 1} of ${accounts.size}")
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            WalletPill(
                text = "Code",
                onClick = { showText = false },
                style = if (showText) WalletPillStyle.Outline else WalletPillStyle.Primary,
                size = WalletPillSize.Compact,
            )
            WalletPill(
                text = "Text",
                onClick = { showText = true },
                style = if (showText) WalletPillStyle.Primary else WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        if (showText) {
            AccountFields(current, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
            // A Single Key's code is its address in letter case, so it is encoded as written. A Mnemonic code is already uppercase UR.
            AnimatedQr(parts = current.parts, modifier = Modifier.fillMaxWidth(), keepCase = current.derivationPath == null)
            Spacer(Modifier.height(LenslySpacing.md))
            AccountAddressDetails(address = current.address, derivationPath = current.derivationPath)
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The export of [count] accounts, behind the App Password, as the pairing code is. Nothing of it is loaded or composed until
 * [checkPassword] accepts the password: [load] runs then. The export locks again when the owner leaves it or the app leaves the
 * foreground. An unavailable vault leaves through [onUnavailable].
 */
@Composable
internal fun AccountExportGate(
    count: Int,
    checkPassword: suspend (CharArray) -> PasswordCheck,
    load: suspend () -> List<AccountExportView>,
    onUnavailable: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var unlocked by remember { mutableStateOf(false) }
    val accounts by produceState<List<AccountExportView>?>(initialValue = null, unlocked) {
        value = if (unlocked) load() else null
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val password = remember { PasswordBuffer() }
    var passwordLength by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                password.clear()
                passwordLength = 0
                unlocked = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            password.clear()
        }
    }
    fun submitPassword() {
        if (checking || password.isEmpty()) return
        val entered = password.toCharArray()
        password.clear()
        passwordLength = 0
        checking = true
        scope.launch {
            val check = try {
                checkPassword(entered)
            } finally {
                entered.fill('\u0000')
            }
            checking = false
            when (check) {
                // A check that ends after the app left the foreground does not unlock the export, as the lock on leaving has already run.
                PasswordCheck.Accepted -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) unlocked = true
                PasswordCheck.Rejected -> Unit
                PasswordCheck.Unavailable -> onUnavailable()
            }
        }
    }
    if (unlocked) {
        accounts?.let { AccountExportScreen(accounts = it, onDone = onDone, onBack = onBack, modifier = modifier) }
    } else {
        PasswordStep(
            busy = checking,
            length = passwordLength,
            onKey = { c -> if (!checking && password.append(c)) passwordLength = password.length },
            onBackspace = {
                if (!checking) {
                    password.backspace()
                    passwordLength = password.length
                }
            },
            onSubmit = { submitPassword() },
            onBack = { if (!checking) onBack() },
            modifier = modifier,
            title = exportTitle(count),
            prompt = EXPORT_PASSWORD_PROMPT,
            stepCount = 2,
            step = 1,
        )
    }
}

/** The plain-text view: the full address, then every other field, each label over its value. Nothing is cut short. */
@Composable
private fun AccountFields(account: AccountExportView, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(LenslySpacing.sm))
        WalletEyebrow("Address")
        Spacer(Modifier.height(LenslySpacing.xxs))
        AddressText(address = account.address, shown = groupedAddress(account.address), icon = false, color = MaterialTheme.colorScheme.onSurface)
        account.fields.forEach { (label, value) -> FieldText(label = label, value = value) }
    }
}

@Composable
private fun FieldText(label: String, value: String) {
    Spacer(Modifier.height(LenslySpacing.sm))
    WalletEyebrow(label)
    Spacer(Modifier.height(LenslySpacing.xxs))
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The full address in groups of four, and the derivation path under a label when there is one. */
@Composable
private fun AccountAddressDetails(address: String, derivationPath: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AddressText(
            address = address,
            shown = groupedAddress(address),
            icon = false,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (derivationPath != null) {
            Spacer(Modifier.height(LenslySpacing.sm))
            WalletEyebrow("Derivation Path")
            Spacer(Modifier.height(LenslySpacing.xxs))
            Text(
                text = derivationPath,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
