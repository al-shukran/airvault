package app.lensly.app.export

import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private val WALLET by lazy { HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art")) }

/** The whole-wallet pairing frame of the BIP39 test vector "abandon x23 art", named "Lensly". */
private const val EXPECTED_WHOLE_WALLET_UR =
    "UR:CRYPTO-HDKEY/ONAXHDCLAXIYTBAADTCMKEKGNECPAAUYSKLSIYVTROJKURRDOYSWBKRKPAAYFHEYGHHHVDIEVYAAHDCXLE" +
        "KGQZVEDRGEZELEZCFRSTGRLECFLATNAXKIMTLTFHHYCHAEGLUYKSTYSTFSPKJKAMTAADDYOEADLNCSDWYKCSFNYKAEYKAOCYGHENT" +
        "SDKAYCYTDKPIMOEASIYGSIHJTJKJZKKTYLRFWGS"

class PairingExportTest {

    @Test
    fun singleFrameCarriesAccountKeyChainCodeAndFingerprints() {
        val frames = PairingExport.urParts(WALLET)
        assertEquals(1, frames.size)
        assertTrue(frames.single().startsWith("UR:CRYPTO-HDKEY/"), frames.single())

        val xpub = WALLET.accountXpub()
        val key = decodeKey(frames)
        assertContentEquals(xpub.publicKey, key.key)
        assertContentEquals(xpub.chainCode, key.chainCode)
        assertContentEquals(fingerprintBytes(xpub.parentFingerprint), key.parentFingerprint)
        assertContentEquals(fingerprintBytes(WALLET.masterFingerprint), key.origin.sourceFingerprint)
        assertEquals(listOf(44, 60, 0), key.origin.components.map { (it as IndexPathComponent).index })
        assertEquals(listOf(true, true, true), key.origin.components.map { (it as IndexPathComponent).isHardened })
        assertEquals("Lensly", key.name)
        assertNull(key.children)
    }

    @Test
    fun wholeWalletFrameKeepsItsBytes() {
        // Regression pin: the whole-wallet pairing that Rabby and MetaMask were verified with must not change.
        assertEquals(EXPECTED_WHOLE_WALLET_UR, PairingExport.urParts(WALLET).single())
    }

    @Test
    fun walletAndXpubOverloadsProduceTheSameParts() {
        assertEquals(
            PairingExport.urParts(WALLET),
            PairingExport.urParts(WALLET.accountXpub(), WALLET.masterFingerprint),
        )
    }

    @Test
    fun longNameSplitsIntoFragmentsThatDecodeAfterOnePass() {
        val name = "Lensly ".repeat(60)
        val frames = PairingExport.urParts(WALLET, name)
        assertTrue(frames.size > 1, "expected several frames, got ${frames.size}")
        assertTrue(frames.all { it.startsWith("UR:CRYPTO-HDKEY/") })
        assertEquals(name, decodeKey(frames).name)
    }

    /** Scans every frame in order, as a receiver does during one pass of the animation, and decodes the result. */
    private fun decodeKey(frames: List<String>): CryptoHDKey {
        val scanner = UrScanner()
        frames.forEach { assertTrue(scanner.receive(it)) }
        val ur = assertNotNull(scanner.result())
        assertEquals("crypto-hdkey", ur.type)
        return ur.decodeFromRegistry() as CryptoHDKey
    }

    private fun fingerprintBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
