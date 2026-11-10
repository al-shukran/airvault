package app.lensly.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.PrivateKey
import app.lensly.core.wallet.toHex
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.wallet.KeyAddressScreen
import app.lensly.ui.wallet.KeyBackupScreen
import app.lensly.ui.wallet.KeyEntryScreen
import app.lensly.ui.wallet.KeyEntryState
import app.lensly.ui.wallet.KeyImportStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Single Key import. The key is typed and checked, its address is shown for confirmation, and then the name screen
 * comes. The write-down is the last step, and it is the one that saves the wallet.
 */
internal fun NavGraphBuilder.importRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.IMPORT_KEY) { HiddenBackdrop { KeyImportRoute(navController) } }
    composable(HiddenRoute.KEY_BACKUP) { HiddenBackdrop { KeyBackupRoute(navController, holder) } }
}

/**
 * Typing the key. The typed characters live in a [PasswordBuffer], and they are zeroed as soon as they are checked. A
 * checked key waits here for the owner to confirm its address, then moves to the draft, which owns it from then on.
 * Back from the address returns to the entry, and the key is zeroed.
 */
@Composable
private fun KeyImportRoute(navController: NavHostController) {
    val draft = draftOf(navController)
    val buffer = remember { PasswordBuffer(initialCapacity = PrivateKey.HEX_DIGITS) }
    var step by remember { mutableStateOf(KeyImportStep.Enter) }
    var length by remember { mutableIntStateOf(0) }
    var refusal by remember { mutableStateOf<String?>(null) }
    // The checked key and its address. The key is null until the owner has passed the check.
    var checked by remember { mutableStateOf<ByteArray?>(null) }
    var address by remember { mutableStateOf("") }

    fun backToEntry() {
        checked?.fill(0)
        checked = null
        step = KeyImportStep.Enter
    }

    DisposableEffect(Unit) {
        onDispose {
            buffer.clear()
            checked?.fill(0)
        }
    }
    BackHandler(enabled = step == KeyImportStep.Address) { backToEntry() }

    when (step) {
        KeyImportStep.Enter -> KeyEntryScreen(
            state = KeyEntryState(length = length, error = refusal),
            onKey = { c ->
                if (buffer.append(c)) {
                    length = buffer.length
                    refusal = null
                }
            },
            onBackspace = {
                buffer.backspace()
                length = buffer.length
                refusal = null
            },
            onDone = {
                val typed = buffer.toCharArray()
                val parsed = try {
                    PrivateKey.parse(typed)
                } finally {
                    typed.fill('\u0000')
                }
                when (parsed) {
                    is PrivateKey.Parse.Rejected -> {
                        refusal = parsed.reason
                    }
                    is PrivateKey.Parse.Ok -> {
                        buffer.clear()
                        length = 0
                        refusal = null
                        checked = parsed.key
                        address = AccountAddresses.addressOfPrivateKey(parsed.key)
                        step = KeyImportStep.Address
                    }
                }
            },
        )
        KeyImportStep.Address -> KeyAddressScreen(
            address = address,
            onConfirm = {
                val key = checked
                if (key != null) {
                    checked = null
                    draft.setSingleKey(key)
                    navController.navigate(HiddenRoute.NAME)
                }
            },
            onBack = { backToEntry() },
        )
    }
}

/**
 * The write-down of an imported key. CREATE WALLET stays off until the owner confirms the copy. Then the wallet is saved
 * from the draft, the draft is wiped, and the wallet's detail opens. The key is the draft's until then.
 */
@Composable
private fun KeyBackupRoute(navController: NavHostController, holder: VaultHolder) {
    val draft = draftOf(navController)
    val key = draft.singleKey
    RequireDraft(navController, ready = key != null)
    val session = rememberOpenSession(navController, holder) ?: return
    if (key == null) return
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val hex = remember(key) { key.toHex() }

    KeyBackupScreen(
        hex = hex,
        copied = copied,
        onCopiedChange = { copied = it },
        onCreate = {
            if (!saving && copied) {
                saving = true
                scope.launch {
                    whileUnlocked(navController, holder) {
                        val walletId = withContext(Dispatchers.IO) {
                            WalletStore(session).createSingleKey(
                                name = draft.name.ifEmpty { "WALLET" },
                                privateKey = key,
                                nowMs = System.currentTimeMillis(),
                            )
                        }
                        draft.wipe()
                        navController.navigate(HiddenRoute.detail(walletId)) {
                            popUpTo(HiddenRoute.HOME) { inclusive = false }
                        }
                    }
                }
            }
        },
    )
}
