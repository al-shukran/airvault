package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaInstruction
import app.airvault.chains.solana.signer.SolanaMessageAccount
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.SolanaWarning
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import fr.acinq.bitcoin.Base58
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

/**
 * The nonce messages Lumio builds: the one-time setup, and a transfer that advances the nonce first. Each expected message is spelled out from its
 * layout, not built by [SolanaNonce]. Each message is then read back by Lensly's decoder, which must find the same instructions and no warning.
 */
class SolanaNonceMessageTest {

    /**
     * A transfer is the legacy layout: the header 1, 0, 2 (one signature, two read-only unsigned keys), then five keys (the account, its nonce
     * account, the recipient, the System Program and the RecentBlockhashes sysvar), the nonce value as the blockhash, and two instructions.
     * AdvanceNonceAccount is first: program 3, accounts [1, 4, 0], data u32 LE 4. Then the transfer: program 3, accounts [0, 2], data u32 LE
     * 2 and u64 LE lamports.
     */
    @Test
    fun theTransferIsByteExactToItsLayout() {
        val expected = bytes(
            byteArrayOf(1, 0, 2),
            byteArrayOf(5),
            ACCOUNT,
            nonceAccountOf(ACCOUNT),
            RECIPIENT,
            SYSTEM,
            RECENT_BLOCKHASHES,
            NONCE_VALUE,
            byteArrayOf(2),
            byteArrayOf(3, 3, 1, 4, 0, 4), le32(4),
            byteArrayOf(3, 2, 0, 2, 12), le32(2), le64(LAMPORTS),
        )
        assertContentEquals(expected, SolanaNonce.transferMessage(ACCOUNT, RECIPIENT, LAMPORTS, NONCE_VALUE))
    }

    /**
     * The setup is the legacy layout with three read-only unsigned keys (the System Program, the RecentBlockhashes and Rent sysvars), and two
     * instructions. CreateAccountWithSeed is program 2 with accounts [0, 1, 0] (funder, new account, base), and its data is u32 LE 3, the 32-byte
     * base, the seed as u64 LE length and bytes, u64 LE lamports, u64 LE space 80 and the 32-byte System owner. InitializeNonceAccount is program 2
     * with accounts [1, 3, 4], and its data is u32 LE 6 and the 32-byte authority.
     */
    @Test
    fun theSetupIsByteExactToItsLayout() {
        val create = bytes(le32(3), ACCOUNT, le64(SEED.size.toLong()), SEED, le64(RENT_LAMPORTS), le64(80), ByteArray(32))
        val initialize = bytes(le32(6), ACCOUNT)
        val expected = bytes(
            byteArrayOf(1, 0, 3),
            byteArrayOf(5),
            ACCOUNT,
            nonceAccountOf(ACCOUNT),
            SYSTEM,
            RECENT_BLOCKHASHES,
            RENT,
            RECENT_BLOCKHASH,
            byteArrayOf(2),
            byteArrayOf(2, 3, 0, 1, 0, create.size.toByte()), create,
            byteArrayOf(2, 3, 1, 3, 4, initialize.size.toByte()), initialize,
        )
        assertContentEquals(expected, SolanaNonce.setupMessage(ACCOUNT, RECENT_BLOCKHASH, RENT_LAMPORTS))
    }

    /** The transfer is one advance first, then one transfer, names the nonce value and warns about nothing. */
    @Test
    fun theTransferDecodesAsAnAdvanceThenATransferWithOnlyTheNonceWarning() {
        val review = review(SolanaNonce.transferMessage(ACCOUNT, RECIPIENT, LAMPORTS, NONCE_VALUE))
        assertEquals(
            listOf(
                SolanaInstruction.AdvanceNonce(at(SYSTEM_ADDRESS), nonceAccount = at(nonceAddress), authority = at(PAYER_ADDRESS)),
                SolanaInstruction.SystemTransfer(
                    at(SYSTEM_ADDRESS),
                    from = at(PAYER_ADDRESS),
                    to = at(RECIPIENT_ADDRESS),
                    lamports = LAMPORTS.toULong(),
                ),
            ),
            review.instructions,
        )
        assertContentEquals(NONCE_VALUE, review.message!!.recentBlockhash)
        assertEquals(listOf(SolanaWarning.DURABLE_NONCE), review.warnings)
    }

    /** The setup reads as one nonce setup: the account pays, is the base, names its seed, and is the authority, with no warning. */
    @Test
    fun theSetupDecodesAsOneNonceSetupPaidByTheAccountWithItsSeed() {
        val review = review(SolanaNonce.setupMessage(ACCOUNT, RECENT_BLOCKHASH, RENT_LAMPORTS))
        assertEquals(
            listOf(
                SolanaInstruction.CreateNonceAccount(
                    programId = at(SYSTEM_ADDRESS),
                    funder = at(PAYER_ADDRESS),
                    account = at(nonceAddress),
                    lamports = RENT_LAMPORTS.toULong(),
                    authority = at(PAYER_ADDRESS),
                    seed = SolanaInstruction.NonceSeed(base = at(PAYER_ADDRESS), text = SEED_TEXT),
                ),
            ),
            review.instructions,
        )
        assertEquals(emptyList(), review.warnings)
    }

    /**
     * The nonce account of a key is createWithSeed of the key, the seed bytes and the 32 zero bytes of the System Program, in that order: SHA-256
     * of those 77 bytes. The known answer is the same one that Lensly's decoder checks, from an independent SHA-256 over the same bytes.
     */
    @Test
    fun theNonceAccountIsCreateWithSeedOfTheKeyTheSeedAndTheSystemProgram() {
        assertContentEquals(nonceAccountOf(ACCOUNT), SolanaNonce.accountOf(ACCOUNT))
        assertEquals(
            "7dee5a4151fbe74220046c3ddc8c51e4abb07cd8446172bf3ee9a9d4089df1fb",
            SolanaNonce.accountOf(ByteArray(32) { 1 }).toHex(),
        )
    }

    /** The nonce value is read from an initialized nonce account of the authority, and from no other data. */
    @Test
    fun theNonceValueIsReadOnlyFromAnInitializedNonceOfTheAuthority() {
        val data = bytes(le32(1), le32(1), ACCOUNT, NONCE_VALUE, le64(5_000L))
        assertContentEquals(NONCE_VALUE, SolanaNonce.nonceValue(data, ACCOUNT))
        assertNull(SolanaNonce.nonceValue(data, RECIPIENT), "another authority")
        assertNull(SolanaNonce.nonceValue(bytes(le32(0), le32(1), ACCOUNT, NONCE_VALUE, le64(5_000L)), ACCOUNT), "the legacy version")
        assertNull(SolanaNonce.nonceValue(bytes(le32(1), le32(0), ACCOUNT, NONCE_VALUE, le64(5_000L)), ACCOUNT), "uninitialized")
        assertNull(SolanaNonce.nonceValue(data.copyOf(79), ACCOUNT), "79 bytes")
        assertNull(SolanaNonce.nonceValue(bytes(data, byteArrayOf(0)), ACCOUNT), "81 bytes")
    }

    /** A transfer to the account, or to its nonce account, would list a key twice, so it is refused before any message is built. */
    @Test
    fun aTransferToTheAccountOrItsNonceAccountIsRefused() {
        assertFailsWith<IllegalArgumentException> { SolanaNonce.transferMessage(ACCOUNT, ACCOUNT, LAMPORTS, NONCE_VALUE) }
        assertFailsWith<IllegalArgumentException> { SolanaNonce.transferMessage(ACCOUNT, nonceAccountOf(ACCOUNT), LAMPORTS, NONCE_VALUE) }
    }

    /** The review of [signData] for the account PAYER, which signs. The check must accept the request. */
    private fun review(signData: ByteArray): SolanaRequestReview {
        val request = SolSignRequest(
            requestId = ByteArray(16) { 0x22 },
            signData = signData,
            derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
            sourceFingerprint = 1,
            address = null,
            origin = null,
            signType = SolSignType.TRANSACTION,
        )
        return assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, listOf(ACCOUNT))).review
    }

    private fun at(address: String): SolanaMessageAccount = SolanaMessageAccount.Static(address)

    private fun bytes(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /** The nonce account of [account] by its layout: SHA-256 over the key, the seed bytes and the System owner. */
    private fun nonceAccountOf(account: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(account + SEED + ByteArray(32))

    private val nonceAddress: String get() = Base58.encode(nonceAccountOf(ACCOUNT))

    private companion object {
        const val SEED_TEXT = "lumio-nonce-0"
        val SEED: ByteArray = SEED_TEXT.toByteArray()
        const val PAYER_ADDRESS = SolanaVectors.PAYER
        const val RECIPIENT_ADDRESS = SolanaVectors.RECIPIENT
        const val SYSTEM_ADDRESS = "11111111111111111111111111111111"
        const val LAMPORTS = 1_500_000_000L
        const val RENT_LAMPORTS = 1_447_680L
        val ACCOUNT: ByteArray = Base58.decode(PAYER_ADDRESS)
        val RECIPIENT: ByteArray = Base58.decode(RECIPIENT_ADDRESS)
        val SYSTEM: ByteArray = Base58.decode(SYSTEM_ADDRESS)
        val RECENT_BLOCKHASHES: ByteArray = Base58.decode("SysvarRecentB1ockHashes11111111111111111111")
        val RENT: ByteArray = Base58.decode("SysvarRent111111111111111111111111111111111")
        val NONCE_VALUE = ByteArray(32) { 0x5a }
        val RECENT_BLOCKHASH = ByteArray(32) { 0x33 }
    }
}
