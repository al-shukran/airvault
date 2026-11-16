package app.lumio.pair

import app.lensly.app.AddressBookEntry
import app.lensly.app.canonicalAddressOf
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Test

/** Three entries of an Address Book, one for each form Lumio reads: an EVM address, a mainnet and a testnet Bitcoin address. */
internal val ADDRESS_BOOK_ALICE = AddressBookEntry("5f0c2a1e-0001", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", "Savings")
internal val ADDRESS_BOOK_COLD = AddressBookEntry("5f0c2a1e-0002", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Cold storage", null)
internal val ADDRESS_BOOK_FAUCET =
    AddressBookEntry("5f0c2a1e-0003", "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7", "Testnet faucet", "Testnet4")

/**
 * The Address Book that a pairing carries, as Lumio reads it. Each address is a valid Ethereum or Bitcoin address, kept in its canonical
 * form, and a code with an address that is neither is refused. The codec bounds a book at 500 entries, so the most a code holds is read.
 */
class PairAddressBookTest {

    @Test
    fun aPairingCarriesItsAddressBookInTheOrderSent() {
        val book = listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD, ADDRESS_BOOK_FAUCET)

        assertEquals(book, PairImport.of(codeOf(pairing(book))).addressBook)
    }

    @Test
    fun aLowerCaseEvmAddressIsKeptInItsCanonicalChecksumForm() {
        val lower = ADDRESS_BOOK_ALICE.copy(address = ADDRESS_BOOK_ALICE.address.lowercase())

        assertEquals(listOf(ADDRESS_BOOK_ALICE), PairImport.of(codeOf(pairing(listOf(lower)))).addressBook)
    }

    @Test
    fun anAddressWithAWrongChecksumRefusesTheCode() {
        val wrong = ADDRESS_BOOK_ALICE.copy(address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAee")

        assertEquals(BAD_ADDRESS, refusalOf(pairing(listOf(ADDRESS_BOOK_COLD, wrong))))
    }

    @Test
    fun anAddressThatIsNotAnEthereumOrBitcoinAddressRefusesTheCode() {
        val text = ADDRESS_BOOK_COLD.copy(address = "not an address")

        assertEquals(BAD_ADDRESS, refusalOf(pairing(listOf(text))))
    }

    @Test
    fun aBookOfTheMostEntriesIsRead() {
        val wallet = HdWallet(PairFixtures.seed)
        val book = List(PairingCodec.MAX_ADDRESS_BOOK) { i ->
            AddressBookEntry("entry-$i", AccountAddresses.addressAt(wallet.accountXpub(), i), "Name $i", null)
        }

        val read = PairImport.of(codeOf(pairing(book))).addressBook

        assertEquals(PairingCodec.MAX_ADDRESS_BOOK, read.size)
        assertEquals(canonicalAddressOf(book.last().address), read.last().address)
    }

    /** The pairing of the test seed's Ethereum account, carrying [book]. */
    private fun pairing(book: List<AddressBookEntry>): PairingPayload {
        val wallet = HdWallet(PairFixtures.seed)
        val ethereum = PairingApp(
            app = "ethereum",
            keys = listOf(PairingKey.ethereumAccount(wallet.accountXpub())),
            accounts = listOf(PairingAccount(key = 0, child = 0, name = "Savings", label = null, group = null)),
        )
        return PairingPayload(
            fingerprint = wallet.masterFingerprint.toLong() and 0xFFFFFFFFL,
            name = "Demo",
            apps = listOf(ethereum),
            addressBook = book,
        )
    }

    private fun codeOf(payload: PairingPayload): UR = UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload))

    private fun refusalOf(payload: PairingPayload): String = assertFailsWith<PairingRefusal> { PairImport.of(codeOf(payload)) }.message.orEmpty()

    private companion object {
        const val BAD_ADDRESS = "The code's Address Book holds an address that is not a valid Ethereum or Bitcoin address."
    }
}
