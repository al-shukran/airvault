package app.lensly.core.solana

import app.lensly.core.wallet.Slip10Ed25519
import app.lensly.core.wallet.toHex
import fr.acinq.bitcoin.Base58
import kotlin.test.assertEquals
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

/**
 * Solana addresses of mnemonic A, the 12-word BIP39 phrase `abandon` x11 followed by `about`, with no passphrase and with the
 * passphrase "TREZOR". The values are the ones in plan section 4.2. They are not checked against a Ledger device yet (WI-22).
 *
 * Lensly holds 24-word phrases only, so the seed comes from BIP39's PBKDF2 step directly, not from `Mnemonic`.
 */
class SolanaAccountTest {

    private val mnemonicA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun bip39SeedWithTrezorPassphraseMatchesThePublishedVector() {
        // The seed column of the trezor python-mnemonic vectors.json entry for 16 zero bytes of entropy.
        assertEquals(TREZOR_SEED, seed("TREZOR").toHex())
    }

    @Test
    fun accountsZeroToTwoMatchWithoutPassphrase() {
        val seed = seed("")
        assertEquals(
            listOf(
                "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL",
                "ANf3TEKFL6jPWjzkndo4CbnNdUNkBk4KHPggJs2nu8Xi",
                "Ag74i82rUZBTgMGLacCA1ZLnotvAca8CLscXcrG6Nwem",
            ),
            (0..2).map { SolanaAccount.address(seed, it) },
        )
    }

    @Test
    fun accountsZeroToTwoMatchWithPassphraseTrezor() {
        val seed = seed("TREZOR")
        assertEquals(
            listOf(
                "9md2ZNCYw622wTzgHn71kh9T6mFSoB4USnA4QdvY8oM4",
                "H6PLX3nyfZA2VM5nd1j11djKTNyLgDhG2uuw6xBpwWBR",
                "77XxboGTJrFAADS2G4WymZQ7VWd1NWC7UR9d4EK8CCo9",
            ),
            (0..2).map { SolanaAccount.address(seed, it) },
        )
    }

    @Test
    fun otherPathShapesFromTheTableMatch() {
        // solanaMain (m/44'/501') and solanaBip44Change (m/44'/501'/0'/0') are not Lensly accounts. They check the SLIP-0010
        // derivation at depths 2 and 4.
        val main = path(44, 501)
        val change = path(44, 501, 0, 0)
        assertEquals("D2PPQSYFe83nDzk96FqGumVU8JA7J8vj2Rhjc2oXzEi5", addressAt(seed(""), main))
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", addressAt(seed(""), change))
        assertEquals("BwwLFb2UdANxF9FUtE56vVEnyScBYrbijgyJBrBRjMkW", addressAt(seed("TREZOR"), main))
        assertEquals("7zSmbu6gKkb6HB7UDPtHYjwCWuBHU1D4TpNZFm4sndQe", addressAt(seed("TREZOR"), change))
    }

    @Test
    fun addressDecodesBackToThePublicKey() {
        val seed = seed("")
        val key = SolanaAccount.publicKey(seed, 0)
        assertEquals(32, key.size)
        assertEquals(key.toHex(), Base58.decode(SolanaAccount.address(seed, 0)).toHex())
    }

    @Test
    fun base58WritesEachLeadingZeroByteAsOne() {
        // The all-zero key is the System Program address. Solana addresses keep their leading zero bytes as leading 1s.
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
    }

    private fun seed(passphrase: String): ByteArray = MnemonicUtils.generateSeed(mnemonicA, passphrase)

    private fun path(vararg indexes: Int): IntArray = IntArray(indexes.size) { Slip10Ed25519.hardened(indexes[it]) }

    private fun addressAt(seed: ByteArray, path: IntArray): String =
        Slip10Ed25519.derive(seed, path).use { Base58.encode(it.publicKey()) }

    private companion object {
        const val TREZOR_SEED =
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
    }
}
