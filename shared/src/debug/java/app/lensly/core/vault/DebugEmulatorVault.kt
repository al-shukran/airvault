package app.lensly.core.vault

import android.content.Context
import android.os.Build
import app.lensly.ui.isEmulator

/**
 * Debug-only: the vault of a debug build on an emulator, whose Keystore key needs no screen lock. An emulator has no screen lock, and the app's
 * vault key needs one, so the app cannot create its vault there. The instrumented tests make the same vault (see [instrumentedVault]), with the
 * same fast key derivation. Null on any other device.
 *
 * This file lives in the debug source set only, so a release build has no way to make this vault.
 */
fun debugEmulatorVault(context: Context): Vault? =
    if (isEmulator(Build.HARDWARE, Build.PRODUCT, Build.FINGERPRINT)) {
        Vault(context, RealClock, kdfOverride = KdfParams(memoryKiB = 8 * 1024, iterations = 1), hardwareKeyUserAuth = false)
    } else {
        null
    }
