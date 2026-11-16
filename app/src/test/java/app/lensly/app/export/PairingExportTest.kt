package app.lensly.app.export

import app.lensly.core.ur.Cbor
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

private const val VERSION = "0.1.0"

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

/** The addresses of Lensly's accounts 0 to 2 for the 12-word test mnemonic, as HdWalletTest pins them. */
private val ACCOUNT_ADDRESSES = listOf(
    "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
    "0x78839F6054d7ed13918bAe0473BA31b1Ca9D7265",
    "0x07B5FdfEB4E11826D233403Fe8Db0611CCF4c231",
)

/**
 * The frames of the export of one account of the "abandon x23 art" wallet, named "Lensly" at version 0.1.0. The export is animated
 * (it is larger than one QR code), so the golden is every frame in order. Re-recorded when the layout changed.
 */
private val EXPECTED_ONE_ACCOUNT_FRAMES: List<String> = listOf(
    "UR:CRYPTO-MULTI-ACCOUNTS/1-2/LPADAOCSTSCYROFRTSKNHDJZONADCYGHENTSDKAOLYTAADDLPDAOWKAXHDCLAXIYTBAADTCMKEKGNECPAAUYSKLSIYVTROJKURRDOYSWBKRKPAAYFHEYGHHHVDIEVYAAHDCXLEKGQZVEDRGEZELEZCFRSTGRLECFLATNAXKIMTLTFHHYCHAEGLUYKSTYSTFSPKJKAMTAADDYOTADLNCSDWYKCSFNYKAEYKAOCYGHENTSDKAXLSPKNSVD",
    "UR:CRYPTO-MULTI-ACCOUNTS/2-2/LPAOAOCSTSCYROFRTSKNHDJZAXATTAADDYOEADLRAEWKAEWKAXAEAYCYTDKPIMOEASIYGSIHJTJKJZKKBKJKHSIAIAJLKPJTJYDMJZIHIEIOIHJPHEJZINKOIHAXIYGSIHJTJKJZKKAAKSDEIEEEEMESIHIDIYEMIEIAENEYENIDESEOEMIHESEHIDENETENETIYENIYEYDYESENDYEODYDYENIAESIDAHIHDYDMEHDMDYAESTWKEMIM",
)

class PairingExportTest {

    @Test
    fun oneAccountIsOneLedgerLiveKeyNamedAfterTheWallet() {
        val xpub = WALLET.accountXpub()
        val frames = PairingExport.urParts(listOf(xpub), WALLET.masterFingerprint, VERSION, "Savings")
        assertTrue(frames.all { it.startsWith("UR:CRYPTO-MULTI-ACCOUNTS/") }, frames.first())

        val root = exportOf(frames)
        assertEquals(WALLET.masterFingerprint.toLong() and 0xFFFFFFFFL, (root.field(1) as Cbor.Uint).value)
        assertEquals("Lensly", (root.field(3) as Cbor.Text).value)
        assertEquals(VERSION, (root.field(5) as Cbor.Text).value)

        val key = keysOf(frames).single()
        assertContentEquals(xpub.publicKey, key.key)
        assertContentEquals(xpub.chainCode, key.chainCode)
        assertContentEquals(fingerprintBytes(xpub.parentFingerprint), key.parentFingerprint)
        assertContentEquals(fingerprintBytes(WALLET.masterFingerprint), key.origin.sourceFingerprint)
        assertEquals(listOf(44, 60, 0), key.origin.components.map { (it as IndexPathComponent).index })
        assertEquals(listOf(true, true, true), key.origin.components.map { (it as IndexPathComponent).isHardened })
        assertEquals(3, key.origin.depth)
        assertEquals(listOf(0, 0), key.children.components.map { (it as IndexPathComponent).index })
        assertEquals("Savings", key.name)
        assertEquals("account.ledger_live", key.note)
    }

    @Test
    fun wholeWalletExportKeepsItsBytes() {
        // Regression pin, re-recorded when the export moved from one crypto-hdkey to the Ledger Live multi-accounts layout.
        assertEquals(
            EXPECTED_ONE_ACCOUNT_FRAMES,
            PairingExport.urParts(listOf(WALLET.accountXpub()), WALLET.masterFingerprint, VERSION, "Lensly"),
        )
    }

    @Test
    fun storedAccountKeysAreTheKeysTheSeedGives() {
        // A wallet's stored account keys are the keys its seed gives, so the MetaMask and Rabby export carries the seed's Ledger Live keys.
        val accounts = 3
        val stored = (0 until accounts).map { WALLET.accountXpub(it) }
        val fromSeed = (0 until accounts).map { HdWallet(SEED).accountXpub(it) }
        val expected = EthereumNexusKeys.ledgerLiveKeys(fromSeed, WALLET.masterFingerprint, "Lensly")
        assertEquals(
            MultiAccountsExport.urParts(WALLET.masterFingerprint, expected, VERSION),
            PairingExport.urParts(stored, WALLET.masterFingerprint, VERSION, "Lensly"),
        )
    }

    @Test
    fun longNameSplitsIntoFragmentsThatDecodeAfterOnePass() {
        val name = "Lensly ".repeat(60)
        val frames = PairingExport.urParts(listOf(WALLET.accountXpub()), WALLET.masterFingerprint, VERSION, name)
        assertTrue(frames.size > 1, "expected several frames, got ${frames.size}")
        assertTrue(frames.all { it.startsWith("UR:CRYPTO-MULTI-ACCOUNTS/") })
        assertEquals(name, keysOf(frames).single().name)
    }

    @Test
    fun derivingChildZeroOfEachExportedKeyGivesTheAddressesOfAccountsZeroToTwo() {
        val wallet = TWELVE_WORD_WALLET
        val frames = PairingExport.urParts((0 until 3).map { wallet.accountXpub(it) }, wallet.masterFingerprint, VERSION, "Lensly")

        val keys = keysOf(frames)
        assertEquals(listOf(0, 1, 2), keys.map { (it.origin.components.last() as IndexPathComponent).index })
        // Child 0/0 of each exported account key, from the public key and chain code alone, is the address of that account.
        val addresses = keys.map { key ->
            val parent = ByteBuffer.wrap(key.parentFingerprint).int
            AccountAddresses.addressAt(AccountXpub(key.key, key.chainCode, parent), 0)
        }
        assertEquals(ACCOUNT_ADDRESSES, addresses)
    }

    /** The frames scanned back in order, as a receiver does during one pass of the animation, as the export's CBOR map. */
    private fun exportOf(frames: List<String>): Cbor.Dict {
        val ur = scanned(frames)
        assertEquals("crypto-multi-accounts", ur.type)
        return Cbor.decode(ur.cborBytes) as Cbor.Dict
    }

    /** The crypto-hdkey of each account key in the export, each read out of its tag 303 map. */
    private fun keysOf(frames: List<String>): List<CryptoHDKey> =
        (exportOf(frames).field(2) as Cbor.Arr).items.map { item ->
            val tagged = item as Cbor.Tagged
            assertEquals(303L, tagged.tag)
            UR("crypto-hdkey", Cbor.encode(tagged.value)).decodeFromRegistry() as CryptoHDKey
        }

    private fun scanned(frames: List<String>): UR {
        val scanner = UrScanner()
        for (frame in frames) {
            scanner.receive(frame)
            if (scanner.result() != null) break
        }
        return assertNotNull(scanner.result())
    }

    private fun Cbor.Dict.field(key: Long): Cbor = entries.first { (k, _) -> (k as Cbor.Uint).value == key }.second

    private fun fingerprintBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
