package app.lensly.app.export

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.toHex
import java.util.Properties
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The Keystone multi-accounts export, checked against an independent reference. The expected CBOR is the one that KeystoneHQ's
 * ur-registry 0.8.1 writes for keys that @scure/bip32 derives (vectors/keystone-multi-accounts), so the derivation and the encoding
 * are both checked, not only Lensly's own code.
 */
class MultiAccountsExportTest {
    private val seed = hexBytes(SEED)
    private val wallet = HdWallet(seed)
    private val ethereum = wallet.accountXpub()
    private val bitcoin = BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET, accountIndex = 0)

    @Test
    fun abandonAboutSeedHasTheDocumentedMasterFingerprint() {
        assertEquals("73c5da0a", "%08x".format(wallet.masterFingerprint))
    }

    @Test
    fun cborIsByteForByteTheKeystoneReferenceEncoding() {
        assertEquals(expectedHex(), MultiAccountsExport.cbor(wallet.masterFingerprint, bitcoin, ethereum, VERSION).toHex())
    }

    @Test
    fun mapCarriesTheFingerprintTheKeysAndTheDeviceFields() {
        val root = Cbor.decode(MultiAccountsExport.cbor(wallet.masterFingerprint, bitcoin, ethereum, VERSION)) as Cbor.Dict
        assertEquals(0x73c5da0aL, (root.field(1) as Cbor.Uint).value)
        assertEquals("Lensly", (root.field(3) as Cbor.Text).value)
        assertEquals("73c5da0a", (root.field(4) as Cbor.Text).value)
        assertEquals(VERSION, (root.field(5) as Cbor.Text).value)
    }

    @Test
    fun keysAreTheFourBitcoinAccountsThenTheEthereumAccount() {
        val root = Cbor.decode(MultiAccountsExport.cbor(wallet.masterFingerprint, bitcoin, ethereum, VERSION)) as Cbor.Dict
        val keys = (root.field(2) as Cbor.Arr).items
        assertEquals(listOf("44'/0'/0'", "49'/0'/0'", "84'/0'/0'", "86'/0'/0'", "44'/60'/0'"), keys.map(::originPath))
    }

    @Test
    fun framesDecodeBackToThePayloadUnderTheRegistryType() {
        val parts = MultiAccountsExport.urParts(wallet.masterFingerprint, bitcoin, ethereum, VERSION)
        assertTrue(parts.first().startsWith("UR:CRYPTO-MULTI-ACCOUNTS/"), parts.first().take(40))
        val scanner = UrScanner()
        for (frame in parts) {
            scanner.receive(frame)
            if (scanner.result() != null) break
        }
        val ur = assertNotNull(scanner.result())
        assertEquals("crypto-multi-accounts", ur.type)
        assertContentEquals(hexBytes(expectedHex()), ur.cborBytes)
    }

    @Test
    fun rejectsBitcoinAccountsOfAnotherSeed() {
        val other = BtcWatchOnly.accounts(ByteArray(16) { 7 }, BtcNetwork.MAINNET, accountIndex = 0)
        assertFailsWith<IllegalArgumentException> {
            MultiAccountsExport.cbor(wallet.masterFingerprint, other, ethereum, VERSION)
        }
    }

    private fun expectedHex(): String {
        val stream = checkNotNull(MultiAccountsExportTest::class.java.getResourceAsStream(VECTOR)) { "missing $VECTOR" }
        return Properties().apply { stream.use { load(it) } }.getProperty("cbor")
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }

    private fun Cbor.Dict.field(key: Long): Cbor = entries.first { (k, _) -> (k as Cbor.Uint).value == key }.second

    /** The origin path of one crypto-hdkey, as `44'/0'/0'`, read from its keypath (key 6, tag 304). */
    private fun originPath(key: Cbor): String {
        val hdKey = (key as Cbor.Tagged).also { assertEquals(303L, it.tag) }.value as Cbor.Dict
        val origin = (hdKey.field(6) as Cbor.Tagged).also { assertEquals(304L, it.tag) }.value as Cbor.Dict
        return (origin.field(1) as Cbor.Arr).items.chunked(2).joinToString("/") { (index, hardened) ->
            "${(index as Cbor.Uint).value}${if ((hardened as Cbor.Bool).value) "'" else ""}"
        }
    }

    private companion object {
        const val VERSION = "0.1.0"
        const val VECTOR = "/vectors/keystone-multi-accounts/abandon-about.txt"

        /** The BIP39 seed of the published "abandon abandon ... about" mnemonic, with no passphrase (vectors/bitcoin/vectors.txt). */
        const val SEED =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    }
}
