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
        // Same viem vector: the private key of account 0.
        val key = artWallet.privateKey(0)
        try {
            assertEquals("1053fae1b3ac64f178bcc21026fd06a3f4544ec2f35338b001f02d1d8efa3d5f", key.toHex())
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun ledgerLiveAccountsZeroToTwoMatchTheKnownAnswers() {
        // The public 12-word test mnemonic "abandon x11 about", empty passphrase. Account N is the Ledger Live address m/44'/60'/N'/0/0.
        // The 12-word seed is used only here.
        val twelveWords = List(11) { "abandon" } + "about"
        val wallet = HdWallet(MnemonicUtils.generateSeed(twelveWords.joinToString(" "), ""))
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", wallet.address(0))
        assertEquals("0x78839F6054d7ed13918bAe0473BA31b1Ca9D7265", wallet.address(1))
        assertEquals("0x07B5FdfEB4E11826D233403Fe8Db0611CCF4c231", wallet.address(2))
    }

    @Test
    fun publishedMnemonicPinsTheEvmAddressesOfAccountsZeroToFour() {
        // The published "abandon ... art" vector, which artWallet holds. Account N is the Ledger Live address m/44'/60'/N'/0/0, which must stay
        // byte-identical. The BIP44 address m/44'/60'/0'/0/N is the one Rabby derives from the account 0 key.
        val wallet = artWallet
        val ledgerLive = listOf(
            "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
            "0x94142B4f665316D3304C3a595ec83aC9C8046598",
            "0xe93E76dFCc3Bb3A6A3A36Bcd9df68cb7C0AD46AA",
            "0x71C2332fc061939f415271D52D24b32f3D4A9173",
            "0x290C2D6fa111595Df8A636d36763bFe4cD8D9e6F",
        )
        val bip44 = listOf(
            "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
            "0xf785bD075874b8423D3583728a981399f31e95aA",
            "0x60Af1c6A5D03F9f1B1b74931499bC99E72fF8DA9",
            "0xDF7F9c4b9B9b9580B265DCD892173e9bD90e067F",
            "0xC1080BB846F3B862d04e72EAd4B92A4D47233D98",
        )
        for (account in 0..4) {
            assertEquals(ledgerLive[account], wallet.address(account), "Ledger Live account $account")
            assertEquals(ledgerLive[account], AccountAddresses.addressAt(wallet.accountXpub(account), 0), "public account $account")
            val key = wallet.privateKey(account)
            try {
                assertEquals(ledgerLive[account], AccountAddresses.addressOfPrivateKey(key), "key of account $account")
            } finally {
                key.fill(0)
            }
            assertEquals(bip44[account], AccountAddresses.addressAt(wallet.accountXpub(0), account), "BIP44 0/$account")
        }
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
        val account = Bip32ECKeyPair.deriveKeyPair(master, intArrayOf(44 or hardened, 60 or hardened, 2 or hardened))
        assertEquals(account.parentFingerprint, artWallet.accountXpub(2).parentFingerprint)
    }

    @Test
    fun accountXpubDerivesSameKeysAsPrivateDerivation() {
        for (account in listOf(0, 2)) {
            val xpub = artWallet.accountXpub(account)
            assertEquals(33, xpub.publicKey.size)
            assertEquals(32, xpub.chainCode.size)

            // Public derivation of m/44'/60'/N'/0/0 from the account key alone must equal the private derivation.
            val external = publicChild(Node(xpub.publicKey, xpub.chainCode), 0)
            val child = publicChild(external, 0)
            val privateKey = artWallet.privateKey(account)
            try {
                val expected = CURVE.g.multiply(BigInteger(1, privateKey)).normalize().getEncoded(true)
                assertContentEquals(expected, child.publicKey, "account $account")
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
