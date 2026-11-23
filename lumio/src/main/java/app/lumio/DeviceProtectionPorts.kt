package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.ui.flow.BiometricEnablePorts
import app.lensly.ui.flow.PasswordCheck
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turning Device Protection on, in Lumio. The App Password check and the seal come from [inner]. Once the seal has worked, Device Protection is
 * ticked off the Finish Setting Up checklist, so the first run and Settings tick it the same way. A tick that fails does not undo the seal.
 */
internal class DeviceProtectionPorts(
    private val inner: BiometricEnablePorts,
    private val holder: VaultHolder,
) : BiometricEnablePorts {
    override suspend fun checkPassword(password: CharArray): PasswordCheck = inner.checkPassword(password)

    override suspend fun enable(): Boolean {
        if (!inner.enable()) return false
        withContext(Dispatchers.IO) {
            holder.openSession()?.let { session -> runCatching { FinishSetupStore.tick(session, FinishItem.DEVICE_PROTECTION) } }
        }
        return true
    }
}
