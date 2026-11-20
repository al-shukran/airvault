package app.lensly.app.export

import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.evm.evmAddressOf
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR
import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The version-7 pairing: the accounts of every chain round-trip, a code of another version is refused by its version, and an encoder never writes a code a reader refuses. */
class PairingCodecTest {

    @Test
    fun accountsOfAllFourChainsRoundTripAsTheyAreEncoded() {
        val payload = RealisticPairing.payload()
        assertEquals(setOf("eip155", "bip122", "solana", "algorand"), payload.accounts.map { it.export.chain.namespace }.toSet())
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aPairingWithNoAddressBookOrLabelsRoundTrips() {
        val payload = withAccounts(RealisticPairing.payload()) { it.copy(label = null, group = null) }.copy(addressBook = emptyList())
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun anAccountNamedByItsAutoNameIsSentWithNoName() {
        val vault = RealisticPairing.payload()
        // An account's Auto-Name counts its index in the path, which for Bitcoin is not its position in the pairing.
        val payload = vault.copy(
            accounts = vault.accounts.map { account ->
                account.copy(name = autoAccountName("Family vault", account.export.signer.path[2].index))
            },
        )
        val text = String(PairingCodec.encode(payload, null), Charsets.ISO_8859_1)
        assertFalse("an Auto-Name is not in the code", text.contains("Family vault 0"))
        assertEquals(payload.accounts.map { it.name }, roundTrip(payload).accounts.map { it.name })
    }

    @Test
    fun aLabelOrGroupIsSentOnceInTheDictionary() {
        val payload = withAccounts(RealisticPairing.payload()) { it.copy(label = "Hodl vault") }
        val text = String(PairingCodec.encode(payload, null), Charsets.ISO_8859_1)
        assertEquals(1, Regex("Hodl vault").findAll(text).count())
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aRunBreaksWhereTheIndexSkipsAndAccountsStillRoundTrip() {
        val accounts = listOf(0, 1, 3, 4).mapIndexed { position, index ->
            PairingAccount(
                export = keyExport(ethereumMainnet, FINGERPRINT, accountSteps(44, 60, index, 0, 0), Curve.SECP256K1, key(position)),
                name = "Account $index",
                label = null,
                group = null,
            )
        }
        val payload = PairingPayload(fingerprint = FINGERPRINT.toLong(), name = "Skips", autoNameBase = "Skips", accounts = accounts)
        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aMinimalBodyWithNoAccountsRoundTrips() {
        val body = Cbor.encode(Cbor.Dict(listOf(uint(2) to uint(7), uint(3) to Cbor.Text("Vault"), uint(4) to Cbor.Text(""), uint(5) to Cbor.Arr(emptyList()))))
        val payload = PairingCodec.decode(envelope(VERSION, body), null)
        assertEquals("Vault", payload.name)
        assertTrue(payload.accounts.isEmpty())
    }

    @Test
    fun aBodyWithAFieldOutsideTheLayoutIsRefused() {
        val body = Cbor.encode(
            Cbor.Dict(listOf(uint(2) to uint(7), uint(3) to Cbor.Text("Vault"), uint(4) to Cbor.Text(""), uint(5) to Cbor.Arr(emptyList()), uint(9) to uint(0))),
        )
        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(envelope(VERSION, body), null) })
    }

    @Test
    fun anOlderVersionIsRefusedByItsVersion() {
        val message = refusalOf { PairingCodec.decode(envelope(6L, byteArrayOf()), null) }
        assertEquals("This pairing uses format 6, and this app reads format 7. Update the app, then pair again.", message)
    }

    @Test
    fun aNewerVersionIsRefusedByItsVersionEvenWithFieldsItDoesNotKnow() {
        val message = refusalOf { PairingCodec.decode(envelope(8L, byteArrayOf()), null) }
        assertEquals("This pairing uses format 8, and this app reads format 7. Update the app, then pair again.", message)
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
    fun aUrTypeThatDiffersOnlyInCaseIsRefusedAndTheExactTypeIsRead() {
        val body = PairingCodec.encode(RealisticPairing.payload(), null)
        assertEquals(
            "This is not a pairing code from Lensly.",
            refusalOf { PairingCodec.fromUr(UR(PairingCodec.UR_TYPE.uppercase(), body), null) },
        )
        assertSamePayload(RealisticPairing.payload(), PairingCodec.fromUr(UR(PairingCodec.UR_TYPE, body), null))
    }

    @Test
    fun anEncoderRefusesAnAccountOfAnotherWallet() {
        val payload = RealisticPairing.payload()
        assertTrue(refusalOf { PairingCodec.encode(payload.copy(fingerprint = FINGERPRINT.toLong() + 1), null) }.isNotEmpty())
    }

    @Test
    fun anEncoderRefusesAKeyOfTheWrongLengthForItsCurve() {
        val payload = withAccounts(RealisticPairing.payload()) { account ->
            if (account.export.chain == ethereumMainnet) account.copy(export = withKey(account.export, ByteArray(32))) else account
        }
        assertTrue(refusalOf { PairingCodec.encode(payload, null) }.contains("33 bytes"))
    }

    @Test
    fun anEncoderRefusesAKeyPathThatIsNotHardenedAtItsAccount() {
        val payload = RealisticPairing.payload()
        val broken = payload.accounts.first().let { first ->
            val path = listOf(PathStep(44, true), PathStep(60, true), PathStep(3, false), PathStep(0, false), PathStep(0, false))
            first.copy(export = withPath(first.export, path))
        }
        assertTrue(refusalOf { PairingCodec.encode(payload.copy(accounts = listOf(broken) + payload.accounts.drop(1)), null) }.isNotEmpty())
    }

    @Test
    fun aNameWithAControlCharacterIsRefused() {
        val payload = withAccounts(RealisticPairing.payload()) { it.copy(name = "Bad\u0007") }
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

    /** The payload with every account replaced by [transform]. */
    private fun withAccounts(payload: PairingPayload, transform: (PairingAccount) -> PairingAccount): PairingPayload =
        payload.copy(accounts = payload.accounts.map(transform))

    private fun withKey(export: AccountExport, publicKey: ByteArray): AccountExport =
        AccountExport(export.chain, export.signer, PublicKey(export.publicKey.curve, publicKey), export.chainCode, export.kind)

    private fun withPath(export: AccountExport, path: List<PathStep>): AccountExport =
        AccountExport(export.chain, SignerRef(export.signer.fingerprint, path), export.publicKey, export.chainCode, export.kind)

    private fun assertSamePayload(expected: PairingPayload, actual: PairingPayload) {
        assertEquals(expected.fingerprint, actual.fingerprint)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.autoNameBase, actual.autoNameBase)
        assertEquals(expected.accounts.map { it.name }, actual.accounts.map { it.name })
        assertEquals(expected.accounts.map { it.label }, actual.accounts.map { it.label })
        assertEquals(expected.accounts.map { it.group }, actual.accounts.map { it.group })
        assertEquals(expected.accounts.map { it.export.chain }, actual.accounts.map { it.export.chain })
        assertEquals(expected.accounts.map { it.export.signer }, actual.accounts.map { it.export.signer })
        assertEquals(expected.accounts.map { it.export.kind }, actual.accounts.map { it.export.kind })
        assertEquals(expected.accounts.map { it.export.publicKey.curve }, actual.accounts.map { it.export.publicKey.curve })
        assertEquals(expected.accounts.map { it.export.publicKey.bytes.toList() }, actual.accounts.map { it.export.publicKey.bytes.toList() })
        assertEquals(expected.accounts.map { it.export.chainCode?.toList() }, actual.accounts.map { it.export.chainCode?.toList() })
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

    /** A compressed secp256k1 key for the codec: its prefix and 32 bytes, which the codec does not check beyond their length. */
    private fun key(seed: Int): ByteArray = byteArrayOf(0x02) + Random(seed).nextBytes(32)

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
        const val VERSION = 7L
        const val FINGERPRINT = 0x1234
    }
}
