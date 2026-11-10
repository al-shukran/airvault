package app.lensly.core.wallet

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Test
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.MnemonicUtils
import org.web3j.utils.Numeric

class HdWalletTest {

    private val artMnemonic = List(23) { "abandon" } + "art"
    private val artWallet by lazy { HdWallet(Mnemonic.toSeed(artMnemonic)) }

    @Test
    fun addressZeroMatchesStandardVector() {
        // viem reference vectors (qntx/kobe vectors/evm.json) and the autodelete/twenty-four test agree.
        assertEquals("0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb", artWallet.address(0))
    }

    @Test
    fun privateKeyZeroMatchesVector() {
        // Same viem vector: private key for m/44'/60'/0'/0/0.
        val key = artWallet.privateKey(0)
        try {
            assertEquals("1053fae1b3ac64f178bcc21026fd06a3f4544ec2f35338b001f02d1d8efa3d5f", key.toHex())
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun laterIndexesMatchVectors() {
        // viem reference vectors for the 12-word "abandon x11 about" mnemonic, empty passphrase.
        // The 12-word seed is used only here, to exercise indexes 1 and 2.
        val twelveWords = List(11) { "abandon" } + "about"
        val wallet = HdWallet(MnemonicUtils.generateSeed(twelveWords.joinToString(" "), ""))
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", wallet.address(1))
        assertEquals("0xb6716976A3ebe8D39aCEB04372f22Ff8e6802D7A", wallet.address(2))
    }

    @Test
    fun passphraseChangesAddress() {
        val withPassphrase = HdWallet(Mnemonic.toSeed(artMnemonic, "TREZOR")).address(0)
        assertNotEquals(artWallet.address(0), withPassphrase)
    }

    @Test
    fun masterFingerprintMatchesBip32Vector1() {
        // BIP32 test vector 1 (bitcoin/bips bip-0032.mediawiki): the master key identifier starts with 3442193e,
        // which is also the parent fingerprint in the xpub of m/0H.
        val wallet = HdWallet(Numeric.hexStringToByteArray("000102030405060708090a0b0c0d0e0f"))
        assertEquals(0x3442193e, wallet.masterFingerprint)
        assertEquals("3442193e", wallet.masterFingerprintHex)
    }

    @Test
    fun parentFingerprintIsFingerprintOfCoinNode() {
        val hardened = Bip32ECKeyPair.HARDENED_BIT
        val master = Bip32ECKeyPair.generateKeyPair(Mnemonic.toSeed(artMnemonic))
        val account = Bip32ECKeyPair.deriveKeyPair(master, intArrayOf(44 or hardened, 60 or hardened, 0 or hardened))
        assertEquals(account.parentFingerprint, artWallet.accountXpub().parentFingerprint)
    }

    @Test
    fun accountXpubDerivesSameKeysAsPrivateDerivation() {
        val xpub = artWallet.accountXpub()
        assertEquals(33, xpub.publicKey.size)
        assertEquals(32, xpub.chainCode.size)

        // Public derivation of m/44'/60'/0'/0/i from the xpub alone must equal the private derivation.
        val external = publicChild(Node(xpub.publicKey, xpub.chainCode), 0)
        for (index in listOf(0, 3)) {
            val child = publicChild(external, index)
            val privateKey = artWallet.privateKey(index)
            try {
                val expected = CURVE.g.multiply(BigInteger(1, privateKey)).normalize().getEncoded(true)
                assertContentEquals(expected, child.publicKey)
            } finally {
                privateKey.fill(0)
            }
        }
    }

    private class Node(val publicKey: ByteArray, val chainCode: ByteArray)

    /** BIP32 CKDpub for a non-hardened child, used only to cross-check the xpub. */
    private fun publicChild(parent: Node, index: Int): Node {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(parent.chainCode, "HmacSHA512"))
        val data = parent.publicKey + byteArrayOf(
            (index ushr 24).toByte(),
            (index ushr 16).toByte(),
            (index ushr 8).toByte(),
            index.toByte(),
        )
        val i = mac.doFinal(data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        val point = CURVE.g.multiply(il).add(CURVE.curve.decodePoint(parent.publicKey)).normalize()
        return Node(point.getEncoded(true), i.copyOfRange(32, 64))
    }

    private companion object {
        val CURVE = SECNamedCurves.getByName("secp256k1")
    }
}
