package app.lensly.app

import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.ur.UrScanner
import kotlin.test.assertNotNull

/**
 * Installs every App in wallet [id], which leaves it as Lensly 1.2.0 left a new wallet: one account of each App at index 0. Tests that need
 * all four Apps call this after [WalletStore.create], so they do not install each App themselves.
 */
fun WalletStore.installAll(id: String) {
    WalletApp.entries.forEach { installApp(id, it) }
}

/** The pairing that [parts] carry, read as the online companion reads it. */
fun pairingOf(parts: List<String>): PairingPayload {
    val scanner = UrScanner()
    parts.forEach { scanner.receive(it) }
    return PairingCodec.fromUr(assertNotNull(scanner.result()), null)
}
