package app.lensly.app.export

import app.lensly.app.WalletApp
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.toHex
import java.util.Properties
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The Keystone multi-accounts export, checked against an independent reference. The expected CBOR is built by the official stack
 * (vectors/keystone-multi-accounts): the keys of @scure/bip32, the encoder of KeystoneHQ's ur-registry 0.8.1, and the parser of
 * keystone-sdk 0.13.0. So the derivation and the encoding are both checked, not only Lensly's own code.
 */
class MultiAccountsExportTest {
    private val seed = hexBytes(SEED)
    private val wallet = HdWallet(seed)
    private val masterFingerprint = wallet.masterFingerprint

    /** The keys of every App in registry order, which is how the export of a wallet is built. */
    private val keys = WalletApp.entries.flatMap { app -> app.nexusKeys(seed, masterFingerprint) }

    @Test
    fun abandonAboutSeedHasTheDocumentedMasterFingerprint() {
        assertEquals("73c5da0a", "%08x".format(wallet.masterFingerprint))
    }

    @Test
    fun cborIsByteForByteTheKeystoneReferenceEncoding() {
        assertEquals(expectedHex(), MultiAccountsExport.cbor(masterFingerprint, keys, VERSION).toHex())
    }

    @Test
    fun mapCarriesTheFingerprintTheDeviceTheDeviceIdAndTheVersion() {
        val root = Cbor.decode(MultiAccountsExport.cbor(masterFingerprint, keys, VERSION)) as Cbor.Dict
        assertEquals(0x73c5da0aL, (root.field(1) as Cbor.Uint).value)
        assertEquals("Lensly", (root.field(3) as Cbor.Text).value)
        assertEquals(expectedDeviceId(), (root.field(4) as Cbor.Text).value)
        assertEquals(VERSION, (root.field(5) as Cbor.Text).value)
    }

    @Test
    fun deviceIdIsFortyHexDigitsOfTheSha256OfTheLenslyTagAndTheFingerprint() {
        assertEquals(expectedDeviceId(), MultiAccountsExport.deviceId(masterFingerprint))
        assertEquals(40, MultiAccountsExport.deviceId(masterFingerprint).length)
    }

    @Test
    fun keysAreTheBitcoinAccountsThenTheEthereumAccountAndItsTwoLedgerKeys() {
        assertEquals(
            listOf("44'/0'/0'", "49'/0'/0'", "84'/0'/0'", "86'/0'/0'", "44'/60'/0'", "44'/60'/0'/0", "44'/60'/0'/0/0"),
            exportedKeys().map { pathText(it.field(6)) },
        )
    }

    @Test
    fun keysCarryTheFieldsOfKeystonesDeviceExport() {
        val items = exportedKeys()
        for (key in items) {
            assertEquals(false, (key.field(2) as Cbor.Bool).value) // is_private
            assertEquals("Lensly", (key.field(9) as Cbor.Text).value) // name
            assertEquals(3L, depthOf(key.field(6))) // origin depth, the depth of the account key
        }
        // Bitcoin: children [*] at depth 0, a chain code, and no note.
        for (key in items.take(4)) {
            assertEquals("*", pathText(key.field(7)))
            assertEquals(0L, depthOf(key.field(7)))
            assertNotNull(key.optional(4L))
            assertNull(key.optional(10L))
        }
        // Ethereum account key: children 0/*, a chain code, and the note account.standard.
        assertEquals("0/*", pathText(items[4].field(7)))
        assertEquals(0L, depthOf(items[4].field(7)))
        assertNotNull(items[4].optional(4L))
        assertEquals("account.standard", (items[4].field(10) as Cbor.Text).value)
        // The Ledger keys: no chain code and no children, and their notes.
        assertEquals("account.ledger_legacy", (items[5].field(10) as Cbor.Text).value)
        assertEquals("account.ledger_live", (items[6].field(10) as Cbor.Text).value)
        for (key in items.drop(5)) {
            assertNull(key.optional(4L))
            assertNull(key.optional(7L))
        }
    }

    @Test
    fun everyAppOfTheRegistryContributesItsKeysInOrder() {
        val bitcoinOnly = WalletApp.BITCOIN.nexusKeys(seed, masterFingerprint)
        val ethereumOnly = WalletApp.ETHEREUM.nexusKeys(seed, masterFingerprint)
        assertEquals(4, bitcoinOnly.size)
        assertEquals(3, ethereumOnly.size)
        // Cbor nodes compare by identity, so the lists are compared through their encoded export.
        assertContentEquals(
            MultiAccountsExport.cbor(masterFingerprint, bitcoinOnly + ethereumOnly, VERSION),
            MultiAccountsExport.cbor(masterFingerprint, keys, VERSION),
        )
    }

    @Test
    fun framesDecodeBackToThePayloadUnderTheRegistryType() {
        val parts = MultiAccountsExport.urParts(masterFingerprint, keys, VERSION)
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
    fun rejectsAnExportWithNoKeys() {
        assertFailsWith<IllegalArgumentException> {
            MultiAccountsExport.cbor(masterFingerprint, emptyList(), VERSION)
        }
    }

    /** The crypto-hdkey maps of the export, in order, each read out of its tag 303. */
    private fun exportedKeys(): List<Cbor.Dict> {
        val root = Cbor.decode(MultiAccountsExport.cbor(masterFingerprint, keys, VERSION)) as Cbor.Dict
        return (root.field(2) as Cbor.Arr).items.map { item ->
            (item as Cbor.Tagged).also { assertEquals(303L, it.tag) }.value as Cbor.Dict
        }
    }

    /** A keypath (tag 304) as `44'/0'/0'`: a wildcard is an empty array, and a hardened step is followed by an apostrophe. */
    private fun pathText(keypath: Cbor): String {
        val map = (keypath as Cbor.Tagged).also { assertEquals(304L, it.tag) }.value as Cbor.Dict
        return (map.field(1) as Cbor.Arr).items.chunked(2).joinToString("/") { (index, hardened) ->
            val step = if (index is Cbor.Arr) "*" else (index as Cbor.Uint).value.toString()
            step + if ((hardened as Cbor.Bool).value) "'" else ""
        }
    }

    /** The depth of a keypath (key 3). */
    private fun depthOf(keypath: Cbor): Long {
        val map = (keypath as Cbor.Tagged).value as Cbor.Dict
        return (map.field(3) as Cbor.Uint).value
    }

    private fun expectedHex(): String = vector().getProperty("cbor")

    private fun expectedDeviceId(): String = vector().getProperty("deviceId")

    private fun vector(): Properties {
        val stream = checkNotNull(MultiAccountsExportTest::class.java.getResourceAsStream(VECTOR)) { "missing $VECTOR" }
        return Properties().apply { stream.use { load(it) } }
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }

    private fun Cbor.Dict.field(key: Long): Cbor = optional(key) ?: throw AssertionError("no field $key")

    private fun Cbor.Dict.optional(key: Long): Cbor? = entries.firstOrNull { (k, _) -> (k as Cbor.Uint).value == key }?.second

    private companion object {
        const val VERSION = "0.1.0"
        const val VECTOR = "/vectors/keystone-multi-accounts/abandon-about.txt"

        /** The BIP39 seed of the published "abandon abandon ... about" mnemonic, with no passphrase (vectors/bitcoin/vectors.txt). */
        const val SEED =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    }
}
