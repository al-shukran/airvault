package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.lensly.core.vault.readPendingPhotoKey
import app.lensly.ui.camera.GalleryScreen
import app.lensly.ui.camera.PhotoStore
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.UnlockScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The media folder that [password] opens, with the photo key of that vault. Null when it is not a password of this vault, or the vault
 * cannot be read. It goes through [Vault.unlock], so it behaves as any unlock: the Failure Delay counts gallery guesses too, and the
 * Duress Password erases the real vault, promotes the Decoy Vault and opens the promoted vault's folder, with no wrong try counted. The
 * session is closed at once, so the vault stays locked, but only after the cleanup of a promotion has finished, since the cleanup needs
 * the session. NonCancellable, so a session that opened is always closed. [password] is zeroed by the vault. A wrong try that is the
 * owner's last allowed one erases the vault, and [onErased] runs instead of a folder.
 */
suspend fun galleryStoreFor(vault: Vault, layout: VaultLayout, password: CharArray, onErased: () -> Unit = {}): PhotoStore? =
    withContext(NonCancellable) {
        try {
            when (val result = vault.unlock(password)) {
                is UnlockResult.Unlocked -> {
                    val session = result.session
                    try {
                        PhotoStore.of(layout, side = session.side, privateKey = session.photoPrivateKey())
                            .also { vault.awaitCleanup() }
                    } finally {
                        session.close()
                    }
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
 * The gallery as the camera opens it. It asks for a vault password first, then shows the folder that the password opens. A wrong
 * password clears the field and says nothing, as the hidden area's gate does. Each visit asks again, and leaving the screen drops the
 * photos. Before the first setup there is no password, so the photos taken so far open at once, as in any gallery.
 */
@Composable
fun GalleryGate(vault: Vault, onBack: () -> Unit, onErased: () -> Unit) {
    val context = LocalContext.current
    val layout = VaultLayout.of(context)
    val scope = rememberCoroutineScope()
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var store by remember { mutableStateOf<PhotoStore?>(null) }
    val beforeSetup = remember { vault.isBeforeSetup() }

    // Leaving the gallery zeroes its photo key, so no key outlives the screen that held it.
    DisposableEffect(Unit) {
        onDispose {
            buffer.clear()
            store?.close()
        }
    }

    LaunchedEffect(beforeSetup) {
        if (beforeSetup) {
            val loaded = withContext(NonCancellable + Dispatchers.IO) {
                PhotoStore.of(layout, side = 0, privateKey = readPendingPhotoKey(context))
            }
            // The screen may have been left while the key was read. Then no screen holds it, so it is zeroed here.
            if (isActive) store = loaded else loaded.close()
        }
    }

    val opened = store
    if (opened != null) {
        GalleryScreen(store = opened, onBack = onBack)
    } else if (!beforeSetup) {
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
                        val loaded = galleryStoreFor(vault, layout, password, onErased)
                        // The screen may have been left while the password was checked. Then no screen holds the key, so it is zeroed here.
                        if (isActive) store = loaded else loaded?.close()
                        busy = false
                    }
                },
            )
        }
    }
}
