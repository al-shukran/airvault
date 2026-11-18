package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.camera.GalleryScreen
import app.lensly.ui.camera.PhotoStore
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.UnlockScreen
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The media folder that [password] opens: the real vault's for the App Password, the Decoy Vault's for the Duress Password. Null when it
 * opens neither, or the vault cannot be read. The check unlocks the vault only to prove the password, and the session is closed at once,
 * so the vault stays locked. It goes through [Vault.unlock], so the Failure Delay counts gallery guesses too. NonCancellable, so a session
 * that opened is always closed. [password] is zeroed by the vault. A wrong try that is the owner's last allowed one erases the vault, and
 * [onErased] runs instead of a folder.
 */
suspend fun galleryStoreFor(vault: Vault, layout: VaultLayout, password: CharArray, onErased: () -> Unit = {}): PhotoStore? =
    withContext(NonCancellable) {
        try {
            when (val result = vault.unlock(password)) {
                is UnlockResult.Unlocked -> {
                    val decoy = result.session.isDecoy
                    result.session.close()
                    PhotoStore.of(layout, decoy = decoy)
                }
                UnlockResult.Rejected -> null
                UnlockResult.Erased -> {
                    onErased()
                    null
                }
            }
        } catch (e: VaultException) {
            null
        }
    }

/**
 * The gallery as the camera cover opens it. It asks for a vault password first, then shows the folder that the password opens. A wrong
 * password clears the field and says nothing, as the hidden area's gate does. Each visit asks again, and leaving the screen drops the
 * photos.
 */
@Composable
fun GalleryGate(vault: Vault, onBack: () -> Unit, onErased: () -> Unit) {
    val layout = VaultLayout.of(LocalContext.current)
    val scope = rememberCoroutineScope()
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var store by remember { mutableStateOf<PhotoStore?>(null) }

    DisposableEffect(Unit) {
        onDispose { buffer.clear() }
    }

    val opened = store
    if (opened != null) {
        GalleryScreen(store = opened, onBack = onBack)
    } else {
        HiddenBackdrop {
            UnlockScreen(
                length = length,
                busy = busy,
                onKey = { c ->
                    if (!busy && buffer.append(c)) length = buffer.length
                },
                onBackspace = {
                    if (!busy) {
                        buffer.backspace()
                        length = buffer.length
                    }
                },
                onSubmit = submit@{
                    if (busy || buffer.isEmpty()) return@submit
                    val password = buffer.toCharArray()
                    buffer.clear()
                    length = 0
                    busy = true
                    scope.launch {
                        store = galleryStoreFor(vault, layout, password, onErased)
                        busy = false
                    }
                },
            )
        }
    }
}
