package app.airvault.chains.solana.signer

import app.airvault.core.model.Danger
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SolanaOriginTest {
    private val signer = ByteArray(32) { 9 }

    /** A version 0 off-chain envelope for the text "hello" in the restricted ASCII format, which the one signer [signer] signs. */
    private fun offchainEnvelope(): ByteArray {
        val domain = byteArrayOf(0xff.toByte()) + "solana offchain".toByteArray()
        val text = "hello".toByteArray()
        return domain + byteArrayOf(0) + ByteArray(32) + byteArrayOf(0, 1) + signer + byteArrayOf(text.size.toByte(), 0) + text
    }

    /** A sol-sign-request for account 0 that asks for a signature over the off-chain message, naming [origin]. */
    private fun checked(origin: String?): SolanaRequestCheck.Result = SolanaRequestCheck.check(
        SolSignRequest(
            requestId = null,
            signData = offchainEnvelope(),
            derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
            sourceFingerprint = null,
            address = null,
            origin = origin,
            signType = SolSignType.MESSAGE,
        ),
        accountKeys = listOf(signer),
    )

    @Test
    fun anOriginIsShownAsTheWalletsUnverifiedClaim() {
        val review = assertIs<SolanaRequestCheck.Result.Ok>(checked("solflare")).review.toReview()

        val rows = review.sections.flatMap { it.rows }
        val row = rows.single { it.label == "Claimed by Request (Not Verified)" }
        assertEquals("solflare", row.value.text())
        assertEquals(listOf("Named by the wallet. Lensly cannot verify it."), row.notes)
        assertTrue(rows.none { it.label == "Origin" })
        assertTrue(review.unaccounted().isEmpty(), "the origin is decoded and shown, so no field is unaccounted")
        assertTrue(review.warnings.none { it.level == Danger.BLOCK })
    }

    @Test
    fun anOriginWithAControlOrInvisibleCharacterIsRefused() {
        assertEquals(SolanaRequestCheck.UNREADABLE_ORIGIN, assertIs<SolanaRequestCheck.Result.Refused>(checked("sol\u0007flare")).reason)
        assertEquals(SolanaRequestCheck.UNREADABLE_ORIGIN, assertIs<SolanaRequestCheck.Result.Refused>(checked("sol‮flare")).reason)
    }
}
