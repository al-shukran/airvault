package app.lensly.app

import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.Mnemonic
import java.security.SecureRandom

/**
 * The first wallet of a new Decoy Vault: a random 24-word seed, under a plausible name. The seed comes from the device's
 * random source alone and shares nothing with a real wallet, so the decoy holds no real key material.
 */
internal object DecoyVault {
    /** The decoy's first wallet name. It reads as an ordinary personal wallet. */
    const val WALLET_NAME = "Everyday"

    private const val ENTROPY_BYTES = 32

    /** Creates the decoy's first wallet in [session], which must be the new, empty decoy session. */
    fun populate(session: VaultSession) {
        val entropy = ByteArray(ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            WalletStore(session).create(
                name = WALLET_NAME,
                deviceGenerated = true,
                words = Mnemonic.fromEntropy(entropy),
                passphrase = null,
                nowMs = System.currentTimeMillis(),
            )
        } finally {
            entropy.fill(0)
        }
    }
}
