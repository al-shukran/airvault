package app.lensly.app.export

import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/** The per-account export of the BIP39 test vector "abandon x23 art", whose account 0 is 0xF278cF59...1cdb. */
class AccountExportTest {

    private val artMnemonic = List(23) { "abandon" } + "art"
    private val artWallet by lazy { HdWallet(Mnemonic.toSeed(artMnemonic)) }
    private val artMeta by lazy {
        WalletMeta(
            id = "art",
            name = "Seed wallet",
            createdAtMs = 0L,
            deviceGenerated = true,
            accountCount = 5,
            masterFingerprintHex = artWallet.masterFingerprintHex,
            accountXpub = artWallet.accountXpub(),
            kind = WalletKind.MNEMONIC,
            autoNameBase = "Seed wallet",
        )
    }

    @Test
    fun aSingleKeyCodeIsItsAddressAndNothingElse() {
        val key = WalletMeta(
            id = "k",
            name = "Key",
            createdAtMs = 0L,
            deviceGenerated = false,
            accountCount = 1,
            masterFingerprintHex = null,
            accountXpub = null,
            kind = WalletKind.SINGLE_KEY,
            autoNameBase = "Key",
        )
        val address = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"

        val view = AccountExport.view(key, 0, address)

        assertEquals(listOf(address), view.parts)
        assertEquals(null, view.derivationPath)
        assertEquals(emptyList(), view.fields)
    }

    @Test
    fun theDerivationPathIsTheExternalChainOfTheAccount() {
        assertEquals("m/44'/60'/0'/0/0", AccountExport.derivationPath(0))
        assertEquals("m/44'/60'/0'/0/3", AccountExport.derivationPath(3))
    }

    @Test
    fun aNegativeIndexHasNoPath() {
        assertFailsWith<IllegalArgumentException> { AccountExport.derivationPath(-1) }
    }

    @Test
    fun theTextViewNamesEveryFieldInFull() {
        val view = AccountExport.view(artMeta, 3, artWallet.address(3))
        val fields = view.fields.toMap()

        assertEquals(
            listOf("Derivation path", "Depth", "Public key", "Chain code", "Parent fingerprint", "Master fingerprint"),
            view.fields.map { it.first },
        )
        assertEquals("m/44'/60'/0'/0/3", fields.getValue("Derivation path"))
        assertEquals("5", fields.getValue("Depth"))
        // 33 bytes and 32 bytes in hex, and 4 bytes of fingerprint: nothing is cut short.
        assertEquals(66, fields.getValue("Public key").length)
        assertEquals(64, fields.getValue("Chain code").length)
        assertEquals(8, fields.getValue("Parent fingerprint").length)
        assertEquals(artWallet.masterFingerprintHex, fields.getValue("Master fingerprint"))
    }

    @Test
    fun theShownPathDerivesTheShownAddress() {
        // Derives each shown path with web3j, which is independent of the xpub code, and compares the address.
        val master = Bip32ECKeyPair.generateKeyPair(Mnemonic.toSeed(artMnemonic))
        for (index in 0..2) {
            val key = Bip32ECKeyPair.deriveKeyPair(master, pathComponents(AccountExport.derivationPath(index)))
            val privateKey = Numeric.toBytesPadded(key.privateKey, PRIVATE_KEY_BYTES)
            try {
                assertEquals(
                    AccountAddresses.addressOfPrivateKey(privateKey),
                    artWallet.address(index),
                    "index $index",
                )
            } finally {
                privateKey.fill(0)
            }
        }
    }

    /**
     * Round trip, for accounts 0 and 3: the code is scanned and decoded again. The key it carries must be the key that web3j derives
     * from the seed along m/44'/60'/0'/0/[index], with its chain code, its origin path and depth, the master fingerprint, and the
     * fingerprint of m/44'/60'/0'/0 as its parent. The address of that private key must be the address shown.
     */
    @Test
    fun theCodeOfEachAccountDecodesToItsOwnAddressKey() {
        val master = Bip32ECKeyPair.generateKeyPair(Mnemonic.toSeed(artMnemonic))
        val external = Bip32ECKeyPair.deriveKeyPair(master, pathComponents("m/44'/60'/0'/0"))
        for (index in listOf(0, 3)) {
            val view = AccountExport.view(artMeta, index, artWallet.address(index))
            val key = decodeKey(view.parts)
            val child = Bip32ECKeyPair.deriveKeyPair(master, pathComponents(AccountExport.derivationPath(index)))

            assertContentEquals(child.publicKeyPoint.getEncoded(true), key.key, "index $index")
            assertContentEquals(child.chainCode, key.chainCode, "index $index")
            assertContentEquals(hash160Prefix(external.publicKeyPoint.getEncoded(true)), key.parentFingerprint, "index $index")
            assertContentEquals(hash160Prefix(master.publicKeyPoint.getEncoded(true)), key.origin.sourceFingerprint, "index $index")
            assertEquals(5, key.origin.depth, "index $index")
            assertEquals(listOf(44, 60, 0, 0, index), key.origin.components.map { (it as IndexPathComponent).index })
            assertEquals(
                listOf(true, true, true, false, false),
                key.origin.components.map { (it as IndexPathComponent).isHardened },
            )

            val privateKey = Numeric.toBytesPadded(child.privateKey, PRIVATE_KEY_BYTES)
            try {
                assertEquals(view.address, AccountAddresses.addressOfPrivateKey(privateKey), "index $index")
            } finally {
                privateKey.fill(0)
            }
        }
    }

    /** Scans the frames of a code in order, as a receiver does during one pass, and decodes the crypto-hdkey. */
    private fun decodeKey(frames: List<String>): CryptoHDKey {
        val scanner = UrScanner()
        frames.forEach { assertTrue(scanner.receive(it)) }
        val ur = assertNotNull(scanner.result())
        return ur.decodeFromRegistry() as CryptoHDKey
    }

    /** The first four bytes of HASH160 of [publicKey], the BIP32 fingerprint. */
    private fun hash160Prefix(publicKey: ByteArray): ByteArray = Hash.sha256hash160(publicKey).copyOfRange(0, 4)

    /** The components of a path such as "m/44'/60'/0'/0/3", with a hardened step as the BIP32 hardened number. */
    private fun pathComponents(path: String): IntArray = path.removePrefix("m/").split("/").map { step ->
        if (step.endsWith("'")) step.dropLast(1).toInt() or Bip32ECKeyPair.HARDENED_BIT else step.toInt()
    }.toIntArray()

    private companion object {
        const val PRIVATE_KEY_BYTES = 32
    }
}
