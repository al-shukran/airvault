package app.lensly.app.export

import app.lensly.app.AddressBookEntry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingCodecTest {

    private val ethereumPath = listOf(0x8000002CL, 0x8000003CL, 0x80000000L)
    private val bitcoinPath = listOf(0x80000054L, 0x80000000L, 0x80000000L)

    private fun key(seed: Int, path: List<Long>, parent: Long = 0x11223344L): PairingKey = PairingKey(
        path = path,
        depth = path.size,
        parentFingerprint = parent,
        publicKey = ByteArray(33) { i -> if (i == 0) 2 else (seed * 31 + i).toByte() },
        chainCode = ByteArray(32) { i -> (seed * 7 + i).toByte() },
    )

    /** A wallet with an Ethereum App whose accounts carry names, labels and groups, and a Bitcoin App with two bare keys. */
    private fun sample(): PairingPayload = PairingPayload(
        fingerprint = 0xDEADBEEFL,
        name = "Demo",
        apps = listOf(
            PairingApp(
                app = "ethereum",
                keys = listOf(key(1, ethereumPath)),
                accounts = listOf(
                    PairingAccount(key = 0, child = 0, name = "Savings", label = "cold", group = "Long term"),
                    PairingAccount(key = 0, child = 1, name = "Trading", label = null, group = "Hot"),
                    PairingAccount(key = 0, child = 2, name = "Trésor ✓", label = "ü", group = null),
                ),
            ),
            PairingApp(
                app = "bitcoin",
                keys = listOf(key(2, bitcoinPath, parent = 0x55667788L), key(3, bitcoinPath, parent = 0x99AABBCCL)),
                accounts = listOf(
                    PairingAccount(key = 0, child = null, name = null, label = null, group = null),
                    PairingAccount(key = 1, child = null, name = null, label = null, group = null),
                ),
            ),
        ),
    )

    private fun assertSamePayload(expected: PairingPayload, actual: PairingPayload) {
        assertEquals(expected.fingerprint, actual.fingerprint)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.apps.map { it.app }, actual.apps.map { it.app })
        for ((want, got) in expected.apps.zip(actual.apps)) {
            assertEquals(want.keys.size, got.keys.size)
            for ((wantKey, gotKey) in want.keys.zip(got.keys)) {
                assertEquals(wantKey.path, gotKey.path)
                assertEquals(wantKey.depth, gotKey.depth)
                assertEquals(wantKey.parentFingerprint, gotKey.parentFingerprint)
                assertTrue(wantKey.publicKey.contentEquals(gotKey.publicKey))
                assertTrue(wantKey.chainCode.contentEquals(gotKey.chainCode))
            }
            assertEquals(want.accounts, got.accounts)
        }
        assertEquals(expected.addressBook, actual.addressBook)
    }

    /** Refusal text of [block], which must throw an IllegalArgumentException. */
    private fun refusalOf(block: () -> Unit): String = try {
        block()
        fail("expected a refusal")
        ""
    } catch (e: IllegalArgumentException) {
        e.message.orEmpty()
    }

    private fun rawPayload(vararg entries: Pair<Long, Cbor>): ByteArray =
        Cbor.encode(Cbor.Dict(entries.map { (key, value) -> Cbor.Uint(key) to value }))

    /** The payload that [bytes] hold once they are encoded without compression and decoded again. */
    private fun roundTrip(payload: PairingPayload): PairingPayload = PairingCodec.decode(PairingCodec.encode(payload, null), null)

    /** The UR that [parts] scan back to, which must be a complete code. */
    private fun scanned(parts: List<String>): UR {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return requireNotNull(scanner.result())
    }

    /** The encoding flag (key 6) of a version-2 envelope. */
    private fun encodingOf(envelope: ByteArray): Long =
        ((Cbor.decode(envelope, app.lensly.core.parse.DecodeBudget("CBOR", 1024)) as Cbor.Dict)
            .entries.single { (key, _) -> (key as Cbor.Uint).value == 6L }.second as Cbor.Uint).value

    /** One Address Book entry as a version-1 payload carries it. */
    private fun addressItem(seed: Int): Cbor = Cbor.Dict(
        listOf(
            Cbor.Uint(1) to Cbor.Text("id-$seed"),
            Cbor.Uint(2) to Cbor.Text("0x" + "%040x".format(seed)),
            Cbor.Uint(3) to Cbor.Text("Alice"),
        ),
    )

    @Test
    fun namesLabelsAndGroupsRoundTrip() {
        val payload = sample()

        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aFullCodeWithoutNamesRoundTripsWithThemAbsent() {
        val payload = PairingPayload(
            fingerprint = 1L,
            name = "Lensly",
            apps = listOf(PairingApp("ethereum", listOf(key(4, ethereumPath)), listOf(PairingAccount(0, 0, null, null, null)))),
        )

        val back = roundTrip(payload)

        assertNull(back.apps.single().accounts.single().name)
        assertNull(back.apps.single().accounts.single().label)
        assertNull(back.apps.single().accounts.single().group)
        assertSamePayload(payload, back)
    }

    @Test
    fun aLongCodeIsAnimatedAndScansBackWhole() {
        val accounts = (0 until 120).map { index ->
            PairingAccount(key = 0, child = index, name = "Account $index", label = "label $index", group = "Group ${index % 5}")
        }
        val payload = PairingPayload(
            fingerprint = 0x0A0B0C0DL,
            name = "Long",
            apps = listOf(PairingApp("ethereum", listOf(key(5, ethereumPath)), accounts)),
        )
        val parts = PairingCodec.urParts(payload, null)
        assertTrue("a long pairing is animated", parts.size > 1)

        assertSamePayload(payload, PairingCodec.fromUr(scanned(parts), null))
    }

    @Test
    fun aNewerVersionIsRefusedByItsVersionEvenWithFieldsItDoesNotKnow() {
        val newer = rawPayload(
            1L to Cbor.Uint(3),
            2L to Cbor.Uint(1),
            3L to Cbor.Text("Demo"),
            4L to Cbor.Arr(emptyList()),
            9L to Cbor.Text("a field from the future"),
        )

        val message = refusalOf { PairingCodec.decode(newer, null) }

        assertTrue(message, message.contains("format 3"))
        assertTrue(message, message.contains("Update the app"))
    }

    @Test
    fun aVersionZeroAndAMissingVersionAreNotAPairing() {
        val notAPairing = "This is not a pairing code from Lensly."
        assertEquals(notAPairing, refusalOf { PairingCodec.decode(rawPayload(1L to Cbor.Uint(0)), null) })
        assertEquals(notAPairing, refusalOf { PairingCodec.decode(rawPayload(2L to Cbor.Uint(1)), null) })
        assertEquals(notAPairing, refusalOf { PairingCodec.decode(byteArrayOf(0x01, 0x02), null) })
    }

    @Test
    fun anotherUrTypeIsRefused() {
        val other = UR("crypto-hdkey", PairingCodec.encode(sample(), null))

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.fromUr(other, null) })
    }

    @Test
    fun aFieldOutsideTheLayoutRefusesTheCode() {
        val bytes = rawPayload(
            1L to Cbor.Uint(1),
            2L to Cbor.Uint(7),
            3L to Cbor.Text("Demo"),
            4L to Cbor.Arr(emptyList()),
            7L to Cbor.Text("extra"),
        )

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(bytes, null) })
    }

    @Test
    fun aPublicKeyThatIsNot33BytesIsRefused() {
        val shortKey = Cbor.Dict(
            listOf(
                Cbor.Uint(1) to Cbor.Arr(ethereumPath.map { Cbor.Uint(it) }),
                Cbor.Uint(2) to Cbor.Uint(3),
                Cbor.Uint(3) to Cbor.Uint(0),
                Cbor.Uint(4) to Cbor.Bytes(ByteArray(32) { 2 }),
                Cbor.Uint(5) to Cbor.Bytes(ByteArray(32)),
            ),
        )
        val app = Cbor.Dict(
            listOf(
                Cbor.Uint(1) to Cbor.Text("ethereum"),
                Cbor.Uint(2) to Cbor.Arr(listOf(shortKey)),
                Cbor.Uint(3) to Cbor.Arr(emptyList()),
            ),
        )
        val bytes = rawPayload(1L to Cbor.Uint(1), 2L to Cbor.Uint(1), 3L to Cbor.Text("Demo"), 4L to Cbor.Arr(listOf(app)))

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(bytes, null) })
    }

    @Test
    fun anEncoderRefusesAnAccountThatNamesNoKey() {
        val broken = PairingPayload(
            fingerprint = 1L,
            name = "Demo",
            apps = listOf(PairingApp("ethereum", listOf(key(6, ethereumPath)), listOf(PairingAccount(1, 0, null, null, null)))),
        )

        assertTrue(refusalOf { PairingCodec.encode(broken, null) }.contains("key the App does not hold"))
    }

    @Test
    fun aNameWithAControlCharacterOrTooManyBytesIsRefused() {
        val withControl = sample().copy(name = "De\u0007mo")
        val tooLong = sample().copy(name = "x".repeat(PairingCodec.MAX_NAME_BYTES + 1))
        val longGroup = sample().copy(
            apps = listOf(sample().apps.first().let { app ->
                app.copy(accounts = listOf(PairingAccount(0, 0, "ok", null, "g".repeat(PairingCodec.MAX_LABEL_BYTES + 1))))
            }),
        )

        assertTrue(refusalOf { PairingCodec.encode(withControl, null) }.contains("1 to ${PairingCodec.MAX_NAME_BYTES} bytes"))
        assertTrue(refusalOf { PairingCodec.encode(tooLong, null) }.contains("1 to ${PairingCodec.MAX_NAME_BYTES} bytes"))
        assertTrue(refusalOf { PairingCodec.encode(longGroup, null) }.contains("1 to ${PairingCodec.MAX_LABEL_BYTES} bytes"))
    }

    @Test
    fun anAddressBookRoundTripsWithALabelAbsentWhenThereIsNone() {
        val payload = sample().copy(
            addressBook = listOf(
                AddressBookEntry(id = "id-1", address = "0x" + "a".repeat(40), name = "Alice", label = "Exchange"),
                AddressBookEntry(id = "id-2", address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", name = "Trésor ✓", label = null),
            ),
        )

        assertSamePayload(payload, roundTrip(payload))
    }

    @Test
    fun aPackedUuidAndChecksumAddressReadBackAsWritten() {
        val checksum = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        val entries = listOf(AddressBookEntry(id = "5f0c2a1e-0001-4b7d-8c2e-9a1f3b5d7e90", address = checksum, name = "Alice", label = null))
        val payload = sample().copy(addressBook = entries)

        assertEquals(entries, roundTrip(payload).addressBook)
        // The same entry with a lower-case address and an upper-case id is text, so it is larger.
        val asText = sample().copy(
            addressBook = listOf(AddressBookEntry(id = "5F0C2A1E-0001-4B7D-8C2E-9A1F3B5D7E90", address = checksum.lowercase(), name = "Alice", label = null)),
        )
        assertEquals(asText.addressBook, roundTrip(asText).addressBook)
        assertTrue(PairingCodec.encode(payload, null).size < PairingCodec.encode(asText, null).size)
    }

    @Test
    fun aVersionOnePairingStillReadsAndTheSameContentRoundTripsAsVersionTwo() {
        val golden = PairingCodec.fromUr(scanned(LabelledPairingGolden.V1_FRAMES), null)

        assertTrue(golden.addressBook.isEmpty())
        assertSamePayload(golden, roundTrip(golden))
    }

    @Test
    fun aFullAddressBookOfTheCapAnimatesAndScansBackWhole() {
        // Each name is 32 emoji, the most a Display Name holds, at four bytes each; each label is 16 emoji.
        val book = List(PairingCodec.MAX_ADDRESS_BOOK) { i ->
            AddressBookEntry(id = "id-$i", address = "0x" + "%040x".format(i), name = "😀".repeat(32), label = "🙂".repeat(16))
        }
        val payload = sample().copy(addressBook = book)

        val parts = PairingCodec.urParts(payload, null)

        assertTrue("a full book is animated", parts.size > 1)
        val back = PairingCodec.fromUr(scanned(parts), null)
        assertEquals(book, back.addressBook)
        assertSamePayload(payload, back)
    }

    @Test
    fun aBookOfMoreThanTheCapIsRefusedByTheEncoderAndTheDecoderAcceptsTheCap() {
        val tooMany = sample().copy(
            addressBook = List(PairingCodec.MAX_ADDRESS_BOOK + 1) { i -> AddressBookEntry("id-$i", "0x" + "%040x".format(i), "Alice", null) },
        )
        assertTrue(refusalOf { PairingCodec.encode(tooMany, null) }.contains("at most ${PairingCodec.MAX_ADDRESS_BOOK} Address Book entries"))

        fun payloadWithBook(count: Int) = rawPayload(
            1L to Cbor.Uint(1),
            2L to Cbor.Uint(7),
            3L to Cbor.Text("Demo"),
            4L to Cbor.Arr(emptyList()),
            5L to Cbor.Arr(List(count) { i -> addressItem(i) }),
        )

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(payloadWithBook(PairingCodec.MAX_ADDRESS_BOOK + 1), null) })
        assertEquals(PairingCodec.MAX_ADDRESS_BOOK, PairingCodec.decode(payloadWithBook(PairingCodec.MAX_ADDRESS_BOOK), null).addressBook.size)
    }

    @Test
    fun anAddressBookEntryWithAMissingFieldOrALongNameIsRefused() {
        val missingAddress = Cbor.Dict(listOf(Cbor.Uint(1) to Cbor.Text("id-1"), Cbor.Uint(3) to Cbor.Text("Alice")))
        val bytes = rawPayload(
            1L to Cbor.Uint(1),
            2L to Cbor.Uint(7),
            3L to Cbor.Text("Demo"),
            4L to Cbor.Arr(emptyList()),
            5L to Cbor.Arr(listOf(missingAddress)),
        )
        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(bytes, null) })

        val longName = sample().copy(
            addressBook = listOf(AddressBookEntry("id-1", "0x" + "a".repeat(40), "n".repeat(PairingCodec.MAX_ENTRY_NAME_BYTES + 1), null)),
        )
        assertTrue(refusalOf { PairingCodec.encode(longName, null) }.contains("1 to ${PairingCodec.MAX_ENTRY_NAME_BYTES} bytes"))
    }

    @Test
    fun aCompressedBodyIsKeptOnlyWhenItIsSmaller() {
        val big = PairingCodec.encode(sample(), DeflateStandIn)
        val tiny = PairingCodec.encode(PairingPayload(fingerprint = 1L, name = "A", apps = emptyList()), DeflateStandIn)

        assertEquals(2L, encodingOf(big))
        assertEquals(0L, encodingOf(tiny))
        assertSamePayload(sample(), PairingCodec.decode(big, DeflateStandIn))
    }

    @Test
    fun aCompressedBodyIsRefusedWithoutADecompressor() {
        val compressed = PairingCodec.encode(sample(), DeflateStandIn)

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(compressed, null) })
    }

    @Test
    fun aBodyThatInflatesPastTheBoundIsRefused() {
        val compressed = PairingCodec.encode(sample(), DeflateStandIn)
        val inflatesTooMuch = BrotliDecompressor { _, _ -> ByteArray(PairingCodec.MAX_BODY_BYTES + 1) }

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(compressed, inflatesTooMuch) })
    }

    @Test
    fun aStoredBodyOverTheBoundIsRefusedAndAnEncoderNeverWritesOne() {
        val envelope = Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1) to Cbor.Uint(2),
                    Cbor.Uint(6) to Cbor.Uint(0),
                    Cbor.Uint(7) to Cbor.Bytes(ByteArray(PairingCodec.MAX_BODY_BYTES + 1)),
                ),
            ),
        )
        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(envelope, null) })

        val huge = sample().copy(
            apps = List(PairingCodec.MAX_APPS) { index ->
                PairingApp(
                    app = "app-$index",
                    keys = listOf(key(index + 10, ethereumPath)),
                    accounts = List(PairingCodec.MAX_ACCOUNTS) { child ->
                        PairingAccount(0, child, "n".repeat(PairingCodec.MAX_NAME_BYTES), "l".repeat(PairingCodec.MAX_LABEL_BYTES), "g".repeat(PairingCodec.MAX_LABEL_BYTES))
                    },
                )
            },
        )
        assertTrue(refusalOf { PairingCodec.encode(huge, null) }.contains("larger than ${PairingCodec.MAX_BODY_BYTES} bytes"))
    }

    @Test
    fun aVersionTwoEnvelopeWithAnUnknownEncodingOrAnExtraKeyIsRefused() {
        val unknownEncoding = Cbor.encode(
            Cbor.Dict(listOf(Cbor.Uint(1) to Cbor.Uint(2), Cbor.Uint(6) to Cbor.Uint(1), Cbor.Uint(7) to Cbor.Bytes(ByteArray(4)))),
        )
        val extraKey = Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1) to Cbor.Uint(2),
                    Cbor.Uint(6) to Cbor.Uint(0),
                    Cbor.Uint(7) to Cbor.Bytes(ByteArray(4)),
                    Cbor.Uint(8) to Cbor.Uint(0),
                ),
            ),
        )

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(unknownEncoding, DeflateStandIn) })
        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(extraKey, DeflateStandIn) })
    }
}
