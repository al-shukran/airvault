package app.lensly.app.export

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

    @Test
    fun namesLabelsAndGroupsRoundTrip() {
        val payload = sample()

        assertSamePayload(payload, PairingCodec.decode(PairingCodec.encode(payload)))
    }

    @Test
    fun aFullCodeWithoutNamesRoundTripsWithThemAbsent() {
        val payload = PairingPayload(
            fingerprint = 1L,
            name = "Lensly",
            apps = listOf(PairingApp("ethereum", listOf(key(4, ethereumPath)), listOf(PairingAccount(0, 0, null, null, null)))),
        )

        val back = PairingCodec.decode(PairingCodec.encode(payload))

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
        val parts = PairingCodec.urParts(payload)
        assertTrue("a long pairing is animated", parts.size > 1)

        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        val ur = scanner.result()

        assertTrue("the scanner completes the code", ur != null)
        assertSamePayload(payload, PairingCodec.fromUr(ur!!))
    }

    @Test
    fun aNewerVersionIsRefusedByItsVersionEvenWithFieldsItDoesNotKnow() {
        val newer = rawPayload(
            1L to Cbor.Uint(2),
            2L to Cbor.Uint(1),
            3L to Cbor.Text("Demo"),
            4L to Cbor.Arr(emptyList()),
            9L to Cbor.Text("a field from the future"),
        )

        val message = refusalOf { PairingCodec.decode(newer) }

        assertTrue(message, message.contains("format 2"))
        assertTrue(message, message.contains("Update the app"))
    }

    @Test
    fun aVersionZeroAndAMissingVersionAreNotAPairing() {
        val notAPairing = "This is not a pairing code from Lensly."
        assertEquals(notAPairing, refusalOf { PairingCodec.decode(rawPayload(1L to Cbor.Uint(0))) })
        assertEquals(notAPairing, refusalOf { PairingCodec.decode(rawPayload(2L to Cbor.Uint(1))) })
        assertEquals(notAPairing, refusalOf { PairingCodec.decode(byteArrayOf(0x01, 0x02)) })
    }

    @Test
    fun anotherUrTypeIsRefused() {
        val other = UR("crypto-hdkey", PairingCodec.encode(sample()))

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.fromUr(other) })
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

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(bytes) })
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

        assertEquals("This is not a pairing code from Lensly.", refusalOf { PairingCodec.decode(bytes) })
    }

    @Test
    fun anEncoderRefusesAnAccountThatNamesNoKey() {
        val broken = PairingPayload(
            fingerprint = 1L,
            name = "Demo",
            apps = listOf(PairingApp("ethereum", listOf(key(6, ethereumPath)), listOf(PairingAccount(1, 0, null, null, null)))),
        )

        assertTrue(refusalOf { PairingCodec.encode(broken) }.contains("key the App does not hold"))
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

        assertTrue(refusalOf { PairingCodec.encode(withControl) }.contains("1 to ${PairingCodec.MAX_NAME_BYTES} bytes"))
        assertTrue(refusalOf { PairingCodec.encode(tooLong) }.contains("1 to ${PairingCodec.MAX_NAME_BYTES} bytes"))
        assertTrue(refusalOf { PairingCodec.encode(longGroup) }.contains("1 to ${PairingCodec.MAX_LABEL_BYTES} bytes"))
    }
}
