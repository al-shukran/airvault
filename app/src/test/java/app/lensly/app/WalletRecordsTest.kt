package app.lensly.app

import app.lensly.core.wallet.AccountXpub
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

class WalletRecordsTest {
    private val id = "11111111-2222-3333-4444-555555555555"
    private val words = List(23) { "abandon" } + "art"
    private val xpub = AccountXpub(
        publicKey = ByteArray(33) { (it + 1).toByte() },
        chainCode = ByteArray(32) { (it + 101).toByte() },
        parentFingerprint = 0x3442193e,
    )

    /** A valid secp256k1 key: it is below the curve order, and it is not zero. */
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun metaRoundTripKeepsEveryField() {
        val meta = WalletMeta(
            id = id,
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
        assertEquals(WalletKind.MNEMONIC, decoded.kind)
        val decodedXpub = requireNotNull(decoded.accountXpub)
        assertContentEquals(xpub.publicKey, decodedXpub.publicKey)
        assertContentEquals(xpub.chainCode, decodedXpub.chainCode)
        assertEquals(xpub.parentFingerprint, decodedXpub.parentFingerprint)
    }

    @Test
    fun singleKeyMetaRoundTripHasNoAccountKeyOrFingerprint() {
        val meta = WalletMeta(
            id = id,
            name = "Imported",
            createdAtMs = 7L,
            deviceGenerated = false,
            accountCount = 1,
            masterFingerprintHex = null,
            accountXpub = null,
            kind = WalletKind.SINGLE_KEY,
        )

        val decoded = WalletRecords.decodeMeta(id, WalletRecords.encodeMeta(meta))

        assertEquals(WalletKind.SINGLE_KEY, decoded.kind)
        assertEquals("Imported", decoded.name)
        assertEquals(1, decoded.accountCount)
        assertNull(decoded.masterFingerprintHex)
        assertNull(decoded.accountXpub)
    }

    @Test
    fun aVersionOneMetaRecordWithoutAKindLoadsAsAMnemonicWallet() {
        val legacy = LegacyWalletRecords.meta(
            name = "Savings",
            createdAtMs = 1_800_000_000_123L,
            deviceGenerated = true,
            accountCount = 3,
            masterFingerprintHex = "3442193e",
            xpub = xpub,
        )

        val decoded = WalletRecords.decodeMeta(id, legacy)

        assertEquals(WalletKind.MNEMONIC, decoded.kind)
        assertEquals("Savings", decoded.name)
        assertEquals(1_800_000_000_123L, decoded.createdAtMs)
        assertEquals(true, decoded.deviceGenerated)
        assertEquals(3, decoded.accountCount)
        assertEquals("3442193e", decoded.masterFingerprintHex)
        val decodedXpub = requireNotNull(decoded.accountXpub)
        assertContentEquals(xpub.publicKey, decodedXpub.publicKey)
        assertContentEquals(xpub.chainCode, decodedXpub.chainCode)
        assertEquals(xpub.parentFingerprint, decodedXpub.parentFingerprint)
    }

    @Test
    fun anUnknownWalletKindIsRejected() {
        val bytes = WalletRecords.encodeMeta(
            WalletMeta(
                id = id,
                name = "Imported",
                createdAtMs = 7L,
                deviceGenerated = false,
                accountCount = 1,
                masterFingerprintHex = null,
                accountXpub = null,
                kind = WalletKind.SINGLE_KEY,
            ),
        )
        bytes[1] = 7
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeMeta(id, bytes) }
    }

    @Test
    fun secretRoundTripWithAndWithoutPassphrase() {
        val plain = assertIs<WalletSecret.Mnemonic>(WalletRecords.decodeSecret(WalletRecords.encodeSecret(words, null)))
        assertEquals(words, plain.words)
        assertNull(plain.passphrase)

        val withPassphrase = assertIs<WalletSecret.Mnemonic>(
            WalletRecords.decodeSecret(WalletRecords.encodeSecret(words, "TREZOR")),
        )
        assertEquals("TREZOR", withPassphrase.passphrase)
    }

    @Test
    fun aVersionOneSecretRecordLoadsAsAMnemonicSecret() {
        val legacy = LegacyWalletRecords.secret(words, passphrase = "TREZOR")

        val secret = assertIs<WalletSecret.Mnemonic>(WalletRecords.decodeSecret(legacy))

        assertEquals(words, secret.words)
        assertEquals("TREZOR", secret.passphrase)
    }

    @Test
    fun singleKeySecretRoundTrips() {
        val secret = assertIs<WalletSecret.SingleKey>(
            WalletRecords.decodeSecret(WalletRecords.encodeSingleKeySecret(key)),
        )

        assertContentEquals(key, secret.privateKey)
    }

    @Test
    fun aSingleKeySecretOutsideTheCurveIsRefused() {
        val zero = WalletRecords.encodeSingleKeySecret(ByteArray(32))
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeSecret(zero) }
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
