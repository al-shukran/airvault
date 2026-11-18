package app.lensly.app.export

import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.lensly.app.evmAddressOf
import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/** The version-6 pairing: the layout round-trips, a code of another version is refused by its version, and an encoder never writes a code a reader refuses. */
class PairingCodecTest {

    @Test
    fun everyChainRoundTripsAsItIsEncoded() {
        val payload = RealisticPairing.payload()
        assertSamePayload(payload, PairingCodec.decode(PairingCodec.encode(payload, null), null))
    }

    @Test
    fun aPairingWithNoAddressBookOrLabelsRoundTrips() {
        val payload = withAccounts(RealisticPairing.payload()) { _, account -> account.copy(label = null, group = null) }
            .copy(addressBook = emptyList())
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun anAccountNamedByItsAutoNameIsSentWithNoName() {
        val vault = RealisticPairing.payload()
        val payload = vault.copy(
            apps = vault.apps.map { app ->
                app.copy(
                    accounts = app.accounts.mapIndexed { position, account ->
                        // An account's Auto-Name counts its index in the path, which for Bitcoin is not its position in the App.
                        val index = (app.keys[position].path[2] and 0x7FFFFFFFL).toInt()
                        account.copy(name = autoAccountName("Family vault", index))
                    },
                )
            },
        )
        val text = String(PairingCodec.encode(payload, null), Charsets.ISO_8859_1)
        assertFalse("an Auto-Name is not in the code", text.contains("Family vault 0"))
        assertEquals(payload.apps.map { app -> app.accounts.map { it.name } }, roundTrip(payload).apps.map { app -> app.accounts.map { it.name } })
    }

    @Test
    fun aLabelOrGroupIsSentOnceInTheDictionary() {
        val payload = withAccounts(RealisticPairing.payload()) { _, account -> account.copy(label = "Hodl vault") }
        val text = String(PairingCodec.encode(payload, null), Charsets.ISO_8859_1)
        assertEquals(1, Regex("Hodl vault").findAll(text).count())
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aRunBreaksWhereTheIndexSkipsAndAccountsStillRoundTrip() {
        val ethereum = PairingApp(
            app = "ethereum",
            keys = listOf(0, 1, 3, 4).map { PairingKey.ethereumAddress(it, address(it)) },
            accounts = listOf(0, 1, 2, 3).map { PairingAccount(key = it, name = "Account $it", label = null, group = null) },
        )
        val payload = PairingPayload(fingerprint = 0x1234L, name = "Skips", autoNameBase = "Skips", apps = listOf(ethereum))
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aMinimalBodyWithNoAppsRoundTrips() {
        val body = Cbor.encode(Cbor.Dict(listOf(uint(2) to uint(7), uint(3) to Cbor.Text("Vault"), uint(4) to Cbor.Text(""), uint(5) to Cbor.Arr(emptyList()))))
        val payload = PairingCodec.decode(envelope(VERSION, body), null)
        assertEquals("Vault", payload.name)
        assertTrue(payload.apps.isEmpty())
    }

    @Test
    fun aBodyWithAFieldOutsideTheLayoutIsRefused() {
        val body = Cbor.encode(
            Cbor.Dict(listOf(uint(2) to uint(7), uint(3) to Cbor.Text("Vault"), uint(4) to Cbor.Text(""), uint(5) to Cbor.Arr(emptyList()), uint(9) to uint(0))),
        )
        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(envelope(VERSION, body), null) })
    }

    @Test
    fun anOlderVersionIsRefusedWithTheMessageToPairAgain() {
        val message = refusalOf { PairingCodec.decode(envelope(5L, byteArrayOf()), null) }
        assertTrue(message, message.contains("older Lensly") && message.contains("pair again"))
    }

    @Test
    fun aNewerVersionIsRefusedByItsVersionEvenWithFieldsItDoesNotKnow() {
        val message = refusalOf { PairingCodec.decode(envelope(7L, byteArrayOf()), null) }
        assertEquals("This pairing uses format 7, and this app reads format 6. Update the app, then pair again.", message)
    }

    @Test
    fun aCodeThatIsNotAPairingOrIsAnotherUrTypeIsRefused() {
        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(byteArrayOf(1, 2, 3), null) })
        assertEquals(
            "This is not a pairing code from Lensly.",
            refusalOf { PairingCodec.fromUr(UR("crypto-seed", byteArrayOf()), null) },
        )
    }

    @Test
    fun aCodeOfThePairingTypeBeforeTwoNineIsRefusedAsNotAPairing() {
        val oldCode = PairingCodec.encode(RealisticPairing.payload(), null)
        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.fromUr(UR("airvault-pairing", oldCode), null) })
    }

    @Test
    fun anEncoderRefusesAnAccountThatNamesAnotherKey() {
        val payload = withAccounts(RealisticPairing.payload()) { _, account -> account.copy(key = account.key + 1) }
        refusalOf { PairingCodec.encode(payload, null) }
    }

    @Test
    fun anEncoderRefusesAKeyPathThatIsNotHardenedAtItsAccount() {
        val payload = RealisticPairing.payload()
        val ethereum = payload.apps.first()
        val broken = ethereum.copy(keys = listOf(ethereum.keys[0].copy(path = listOf(0x80000000L + 44, 0x80000000L + 60, 3, 0, 0))) + ethereum.keys.drop(1))
        assertTrue(refusalOf { PairingCodec.encode(payload.copy(apps = listOf(broken)), null) }.isNotEmpty())
    }

    @Test
    fun aNameWithAControlCharacterIsRefused() {
        val payload = withAccounts(RealisticPairing.payload()) { _, account -> account.copy(name = "Bad\u0007") }
        assertTrue(refusalOf { PairingCodec.encode(payload, null) }.contains("1 to 96 bytes"))
    }

    @Test
    fun anAddressBookRoundTripsWithPackedIdsAndAddresses() {
        val payload = RealisticPairing.payload()
        assertEquals(12, payload.addressBook.size)
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aBookOfMoreThanTheCapIsRefusedByTheEncoder() {
        val payload = RealisticPairing.payload().copy(addressBook = List(PairingCodec.MAX_ADDRESS_BOOK + 1) { entry(it) })
        assertTrue(refusalOf { PairingCodec.encode(payload, null) }.contains("Address Book entries"))
    }

    @Test
    fun aFullAddressBookOfTheCapAnimatesAndScansBackWhole() {
        val payload = RealisticPairing.payload().copy(addressBook = List(PairingCodec.MAX_ADDRESS_BOOK) { entry(it) })
        val frames = PairingCodec.urParts(payload, null)
        assertTrue(frames.size > 1)
        val scanner = UrScanner()
        frames.forEach { scanner.receive(it) }
        assertSamePayload(payload, PairingCodec.fromUr(requireNotNull(scanner.result()), null))
    }

    @Test
    fun framesCarryTheFragmentSizeAndScanBackWhole() {
        val payload = RealisticPairing.payload()
        val bytes = PairingCodec.encode(payload, null)
        val frames = PairingCodec.urParts(payload, null)
        assertEquals((bytes.size + PairingCodec.FRAGMENT_BYTES - 1) / PairingCodec.FRAGMENT_BYTES, frames.size)
        val scanner = UrScanner()
        frames.forEach { scanner.receive(it) }
        assertSamePayload(payload, PairingCodec.fromUr(requireNotNull(scanner.result()), null))
    }

    @Test
    fun aCompressedBodyIsKeptOnlyWhenItIsSmaller() {
        val payload = RealisticPairing.payload()
        val larger = PairingCodec.encode(payload, BrotliCompressor { it.copyOf(it.size + 1) })
        assertEquals(0L, encodingOf(larger))
        val smaller = PairingCodec.encode(payload, BrotliCompressor { it.copyOf(it.size / 2) })
        assertEquals(2L, encodingOf(smaller))
        assertTrue(refusalOf { PairingCodec.decode(smaller, null) }.isNotEmpty())
    }

    private fun roundTrip(payload: PairingPayload): PairingPayload = PairingCodec.decode(PairingCodec.encode(payload, null), null)

    /** The payload with every account of every App replaced by [transform], which is given the App's id and the account. */
    private fun withAccounts(payload: PairingPayload, transform: (String, PairingAccount) -> PairingAccount): PairingPayload =
        payload.copy(apps = payload.apps.map { app -> app.copy(accounts = app.accounts.map { transform(app.app, it) }) })

    private fun assertSamePayload(expected: PairingPayload, actual: PairingPayload) {
        assertEquals(expected.fingerprint, actual.fingerprint)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.autoNameBase, actual.autoNameBase)
        assertEquals(expected.apps.map { it.app }, actual.apps.map { it.app })
        for ((want, got) in expected.apps.zip(actual.apps)) {
            assertEquals(want.accounts, got.accounts)
            assertEquals(want.keys.map { it.path }, got.keys.map { it.path })
            assertEquals(want.keys.map { it.material.toList() }, got.keys.map { it.material.toList() })
            assertEquals(want.keys.map { it.chainCode?.toList() }, got.keys.map { it.chainCode?.toList() })
        }
        assertEquals(expected.addressBook, actual.addressBook)
    }

    /** The message of the refusal that [block] throws, or a failure when [block] is accepted. */
    private fun refusalOf(block: () -> Unit): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("the code was accepted")
        return ""
    }

    /** The envelope of [version] that holds [body] as it is, which the reader checks before it reads the body. */
    private fun envelope(version: Long, body: ByteArray): ByteArray =
        Cbor.encode(Cbor.Dict(listOf(uint(1) to uint(version), uint(6) to uint(0), uint(7) to Cbor.Bytes(body))))

    private fun encodingOf(envelope: ByteArray): Long {
        val root = Cbor.decode(envelope, DecodeBudget("CBOR", ParserLimits.MAX_PAIRING_ITEMS)) as Cbor.Dict
        return ((root.entries.first { (key, _) -> (key as Cbor.Uint).value == 6L }.second) as Cbor.Uint).value
    }

    private fun address(seed: Int): String = checkNotNull(evmAddressOf("0x" + Random(seed).nextBytes(20).joinToString("") { "%02x".format(it) }))

    private fun entry(index: Int): AddressBookEntry {
        val random = Random(index)
        return AddressBookEntry(
            id = UUID(random.nextLong(), random.nextLong()).toString(),
            address = address(index),
            name = "Contact ${index + 1}",
            label = if (index % 2 == 0) "Family" else null,
        )
    }

    private fun uint(value: Long): Cbor = Cbor.Uint(value)

    private companion object {
        const val VERSION = 6L
    }
}
