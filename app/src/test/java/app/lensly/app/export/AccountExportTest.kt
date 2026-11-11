package app.lensly.app.export

import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import java.nio.ByteBuffer
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
    fun aMnemonicCodeIsThePairingCodeOfTheAccountKey() {
        val view = AccountExport.view(artMeta, 0, artWallet.address(0))

        assertEquals(
            PairingExport.urParts(artWallet.accountXpub(), artWallet.masterFingerprint, "Seed wallet"),
            view.parts,
        )
        assertEquals(artWallet.address(0), view.address)
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
            listOf("Derivation path", "Origin path", "Account public key", "Chain code", "Parent fingerprint", "Master fingerprint"),
            view.fields.map { it.first },
        )
        assertEquals("m/44'/60'/0'/0/3", fields.getValue("Derivation path"))
        assertEquals("m/44'/60'/0'", fields.getValue("Origin path"))
        // 33 bytes and 32 bytes in hex, and 4 bytes of fingerprint: nothing is cut short.
        assertEquals(66, fields.getValue("Account public key").length)
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
     * Round trip: the code is scanned and decoded again. The key it carries must be the account key that web3j derives from the seed
     * along m/44'/60'/0', with its chain code and both fingerprints, and the addresses that key derives by public derivation must
     * equal the addresses web3j derives along the shown paths.
     */
    @Test
    fun theAccountKeyInTheCodeDerivesEveryShownAddress() {
        val key = decodeKey(AccountExport.view(artMeta, 0, artWallet.address(0)).parts)

        val master = Bip32ECKeyPair.generateKeyPair(Mnemonic.toSeed(artMnemonic))
        val coin = Bip32ECKeyPair.deriveKeyPair(master, pathComponents("m/44'/60'"))
        val account = Bip32ECKeyPair.deriveKeyPair(master, pathComponents("m/44'/60'/0'"))
        assertContentEquals(account.publicKeyPoint.getEncoded(true), key.key)
        assertContentEquals(account.chainCode, key.chainCode)
        assertContentEquals(hash160Prefix(coin.publicKeyPoint.getEncoded(true)), key.parentFingerprint)
        assertContentEquals(hash160Prefix(master.publicKeyPoint.getEncoded(true)), key.origin.sourceFingerprint)
        assertEquals(listOf(44, 60, 0), key.origin.components.map { (it as IndexPathComponent).index })

        val xpub = AccountXpub(
            publicKey = key.key,
            chainCode = key.chainCode,
            parentFingerprint = ByteBuffer.wrap(key.parentFingerprint).int,
        )
        for (index in 0..4) {
            val child = Bip32ECKeyPair.deriveKeyPair(account, intArrayOf(0, index))
            val privateKey = Numeric.toBytesPadded(child.privateKey, PRIVATE_KEY_BYTES)
            try {
                assertEquals(
                    AccountAddresses.addressOfPrivateKey(privateKey),
                    AccountAddresses.addressAt(xpub, index),
                    "index $index",
                )
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
