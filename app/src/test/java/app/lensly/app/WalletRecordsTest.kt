package app.lensly.app

import app.lensly.core.wallet.AccountXpub
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class WalletRecordsTest {
    private val xpub = AccountXpub(
        publicKey = ByteArray(33) { (it + 1).toByte() },
        chainCode = ByteArray(32) { (it + 101).toByte() },
        parentFingerprint = 0x3442193e,
    )

    @Test
    fun metaRoundTripKeepsEveryField() {
        val meta = WalletMeta(
            id = "11111111-2222-3333-4444-555555555555",
            name = "Savings",
            createdAtMs = 1_800_000_000_123L,
            deviceGenerated = true,
            accountCount = 3,
            masterFingerprintHex = "3442193e",
            accountXpub = xpub,
        )

        val decoded = WalletRecords.decodeMeta(meta.id, WalletRecords.encodeMeta(meta))

        assertEquals(meta.name, decoded.name)
        assertEquals(meta.createdAtMs, decoded.createdAtMs)
        assertEquals(meta.deviceGenerated, decoded.deviceGenerated)
        assertEquals(meta.accountCount, decoded.accountCount)
        assertEquals(meta.masterFingerprintHex, decoded.masterFingerprintHex)
        assertContentEquals(xpub.publicKey, decoded.accountXpub.publicKey)
        assertContentEquals(xpub.chainCode, decoded.accountXpub.chainCode)
        assertEquals(xpub.parentFingerprint, decoded.accountXpub.parentFingerprint)
    }

    @Test
    fun secretRoundTripWithAndWithoutPassphrase() {
        val words = List(23) { "abandon" } + "art"

        val plain = WalletRecords.decodeSecret(WalletRecords.encodeSecret(words, null))
        assertEquals(words, plain.words)
        assertNull(plain.passphrase)

        val withPassphrase = WalletRecords.decodeSecret(WalletRecords.encodeSecret(words, "TREZOR"))
        assertEquals("TREZOR", withPassphrase.passphrase)
    }

    @Test
    fun unknownRecordVersionIsRejected() {
        val bytes = WalletRecords.encodeSecret(List(24) { "abandon" }, null)
        bytes[0] = 9
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeSecret(bytes) }
    }

    @Test
    fun secretWithoutWordsIsRefused() {
        assertFailsWith<IllegalArgumentException> { WalletRecords.encodeSecret(emptyList(), null) }
    }
}
