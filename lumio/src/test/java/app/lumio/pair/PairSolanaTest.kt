package app.lumio.pair

import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.core.model.Curve
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.accountSteps
import app.lensly.app.export.keyExport
import app.lensly.app.export.PairingPayload
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import com.sparrowwallet.hummingbird.UR
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Solana accounts of a Lensly pairing, as Lumio reads them. The seed is the 12-word BIP39 phrase `abandon` x11 then `about`, with no
 * passphrase (plan section 4.2). Lensly holds 24-word phrases only, so this test derives the same seed from BIP39's PBKDF2 step directly.
 */
class PairSolanaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun lumioShowsTheThreeSolanaAddressesOfALensPairingAfterARoundTrip() {
        val pairing = roundTrip(payload(solana = solanaKeys()))

        PairedWallets.save(session, pairing, nowMs = 100L)

        val listed = PairedWallets.list(session).single()
        assertEquals(EXPECTED_ADDRESSES, listed.solana.map { SolanaAccount.addressOf(it) })
        assertEquals(setOf(PairChain.EVM, PairChain.SOLANA), listed.families)
    }

    @Test
    fun aSolanaKeyOnAnotherPathIsRefused() {
        // Each account is one account on from its position, so the first account is not numbered 0.
        val wrongPath = (0 until 3).map { index ->
            PairingAccount(
                keyExport(SOLANA_MAINNET, PairFixtures.fingerprint(SEED), accountSteps(44, 501, index + 1), Curve.ED25519_SLIP10, SolanaAccount.publicKey(SEED, index)),
                "Solana $index",
                null,
                null,
            )
        }

        assertThrows(PairingRefusal::class.java) { roundTrip(payload(solana = wrongPath)) }
    }

    @Test
    fun aPairingWithoutSolanaHoldsNoSolanaAccount() {
        PairedWallets.save(session, roundTrip(payload(solana = emptyList())), nowMs = 100L)

        assertEquals(emptyList<ByteArray>(), PairedWallets.list(session).single().solana)
    }

    /** The pairing as Lumio reads it: the code is encoded as Lensly writes it, uncompressed, and checked by [PairImport]. */
    private fun roundTrip(payload: PairingPayload): PairingPayload =
        PairImport.of(UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload, null)))

    /** The Solana accounts 0 to 2, as Lensly's pairing carries them: the public key of each, at its path. */
    private fun solanaKeys(): List<PairingAccount> = (0 until 3).map { index -> PairFixtures.solanaAccount(index, "Solana $index", SEED) }

    private fun payload(solana: List<PairingAccount>): PairingPayload =
        PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Main", seed = SEED)) + solana, seed = SEED, name = "Demo", base = "Demo")

    private companion object {
        /** The 64-byte BIP39 seed of the 12-word phrase, with no passphrase. */
        val SEED: ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(
                PBEKeySpec(
                    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".toCharArray(),
                    "mnemonic".toByteArray(Charsets.UTF_8),
                    2048,
                    512,
                ),
            ).encoded

        /** The addresses of accounts 0 to 2 of that seed, as plan section 4.2 and the Lensly tests give them. */
        val EXPECTED_ADDRESSES = listOf(
            "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL",
            "ANf3TEKFL6jPWjzkndo4CbnNdUNkBk4KHPggJs2nu8Xi",
            "Ag74i82rUZBTgMGLacCA1ZLnotvAca8CLscXcrG6Nwem",
        )
    }
}
