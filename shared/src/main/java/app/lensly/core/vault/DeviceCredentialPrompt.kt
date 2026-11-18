package app.lensly.core.vault

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Asks the owner for the screen lock or a strong biometric, and reports whether they passed. The vault calls it when the hardware key
 * needs a fresh authentication. A cancel, a dismissed prompt, or any error that ends the prompt reports false.
 */
// USE_BIOMETRIC is declared by the hosting apps (app and lumio manifests). This library's manifest cannot show lint that.
@SuppressLint("MissingPermission")
internal suspend fun confirmDeviceCredential(context: Context): Boolean = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle("Unlock")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(signal, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(true, null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(false, null)
            }
        })
    }
}
