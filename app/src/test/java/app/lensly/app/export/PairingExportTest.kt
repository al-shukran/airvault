package app.lensly.app.export

import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

/** The BIP39 seed of the test vector "abandon x23 art", with no passphrase. */
private val SEED by lazy { Mnemonic.toSeed(List(23) { "abandon" } + "art") }
private val WALLET by lazy { HdWallet(SEED) }

/**
 * The public 12-word test mnemonic "abandon x11 about". A 12-word phrase is not a [Mnemonic], so its seed comes from web3j,
 * as in HdWalletTest.
 */
private val TWELVE_WORD_WALLET by lazy {
    HdWallet(MnemonicUtils.generateSeed(List(11) { "abandon" }.plus("about").joinToString(" "), ""))
}

/** The address of Lensly's account 0 for the 12-word test mnemonic, as HdWalletTest pins it. */
private const val ACCOUNT_0_ADDRESS = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

class PairingExportTest {

    @Test
    fun theCodeIsOneCryptoHdKeyOfTheStandardAccountKeyNamedAfterTheWallet() {
        val xpub = WALLET.accountXpub()
        val frames = PairingExport.urParts(listOf(xpub), WALLET.masterFingerprint, "Savings")
        assertEquals(1, frames.size, "a Rabby sync reads one code")
        assertTrue(frames.single().startsWith("UR:CRYPTO-HDKEY/"), frames.single())

        val key = keyOf(frames)
        assertContentEquals(xpub.publicKey, key.key)
        assertContentEquals(xpub.chainCode, key.chainCode)
        assertContentEquals(fingerprintBytes(xpub.parentFingerprint), key.parentFingerprint)
        assertContentEquals(fingerprintBytes(WALLET.masterFingerprint), key.origin.sourceFingerprint)
        // The BIP44 Ethereum account key m/44'/60'/0', which Rabby reads as its standard keyring.
        assertEquals(listOf(44, 60, 0), key.origin.components.map { (it as IndexPathComponent).index })
        assertEquals(listOf(true, true, true), key.origin.components.map { (it as IndexPathComponent).isHardened })
        assertEquals(3, key.origin.depth)
        assertEquals("Savings", key.name)
    }

    @Test
    fun theImporterDerivesAccountZeroAsChildZeroOfTheExportedKey() {
        // Rabby derives its address i from the key as child 0/i, which is m/44'/60'/0'/0/i. Child 0/0 is Lensly's account 0.
        val wallet = TWELVE_WORD_WALLET
        val key = keyOf(PairingExport.urParts(listOf(wallet.accountXpub()), wallet.masterFingerprint, "Lensly"))

        val parent = ByteBuffer.wrap(key.parentFingerprint).int
        assertEquals(ACCOUNT_0_ADDRESS, AccountAddresses.addressAt(AccountXpub(key.key, key.chainCode, parent), 0))
    }

    /** The one crypto-hdkey of [frames], scanned back as a receiver does during one pass. */
    private fun keyOf(frames: List<String>): CryptoHDKey {
        val ur = scanned(frames)
        assertEquals("crypto-hdkey", ur.type)
        return ur.decodeFromRegistry() as CryptoHDKey
    }

    private fun scanned(frames: List<String>): UR {
        val scanner = UrScanner()
        for (frame in frames) {
            scanner.receive(frame)
            if (scanner.result() != null) break
        }
        return assertNotNull(scanner.result())
    }

    private fun fingerprintBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
