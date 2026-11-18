package app.lensly.core.vault

import android.content.Context

/**
 * The vault that the instrumented tests use. It is the production vault on the real Keystore, except that its hardware key needs no
 * device authentication. The emulators that run the tests have no screen lock, and Android refuses such a key without one. Production
 * code never reaches this: it is a test fixture, so it is not in the app.
 */
fun instrumentedVault(context: Context, clock: Clock, kdfOverride: KdfParams?): Vault =
    Vault(context, clock, kdfOverride, hardwareKeyUserAuth = false)
