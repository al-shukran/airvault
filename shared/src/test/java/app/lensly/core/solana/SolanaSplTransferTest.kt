package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaInstruction
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.lensly.core.ur.PathStep
import fr.acinq.bitcoin.Base58
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Tests for [SolanaSplTransfer] and [SolanaTokenAccounts]. The messages are the `spl-send-nonce-*` rows of vectors.txt, which solders 0.29.0
 * built (see the README in that folder). Keys are SHA-256 of their labels, and the token accounts are the ones solders derived.
 */
class SolanaSplTransferTest {
    private val payer = Base58.decode(SolanaVectors.PAYER)
    private val recipient = Base58.decode(SolanaVectors.RECIPIENT)
    private val usdc = Base58.decode(SolanaVectors.USDC)
    private val nonceValue = MessageDigest.getInstance("SHA-256").digest("lensly spl send nonce value".toByteArray())

    @Test
    fun aSendToANewTokenAccountIsByteForByteTheSolanaMessage() {
        val message = SolanaSplTransfer.message(payer, recipient, usdc, 1_234_567UL, 6, nonceValue, createRecipientAccount = true)
        assertContentEquals(SolanaVectors["spl-send-nonce-create-ata"], message)
    }

    @Test
    fun aSendToAnExistingTokenAccountLeavesOutTheCreateAndIsByteForByteTheSolanaMessage() {
        val message = SolanaSplTransfer.message(payer, recipient, usdc, 1_234_567UL, 6, nonceValue, createRecipientAccount = false)
        assertContentEquals(SolanaVectors["spl-send-nonce-existing-ata"], message)
    }

    @Test
    fun theTokenAccountsAreTheAddressesSolanaDerives() {
        assertEquals(SENDER_ATA, Base58.encode(SolanaTokenAccounts.associatedAddress(payer, usdc)))
        assertEquals(RECIPIENT_ATA, Base58.encode(SolanaTokenAccounts.associatedAddress(recipient, usdc)))
        assertEquals(SolanaVectors.USDC_ATA, Base58.encode(SolanaTokenAccounts.associatedAddress(Base58.decode(SolanaVectors.OWNER), usdc)))
    }

    @Test
    fun aDerivedTokenAccountIsOffTheCurveAndTheBasePointIsOnIt() {
        val basePoint = ByteArray(32) { 0x66 }.also { it[0] = 0x58 }
        assertTrue(SolanaTokenAccounts.onCurve(basePoint))
        assertFalse(SolanaTokenAccounts.onCurve(Base58.decode(SENDER_ATA)))
        assertFalse(SolanaTokenAccounts.onCurve(Base58.decode(RECIPIENT_ATA)))
    }

    @Test
    fun aSendIsRefusedForItsOwnRecipientAZeroAmountTooManyDecimalsOrAShortKey() {
        assertFailsWith<IllegalArgumentException> { SolanaSplTransfer.message(payer, payer, usdc, 1UL, 6, nonceValue, false) }
        assertFailsWith<IllegalArgumentException> { SolanaSplTransfer.message(payer, recipient, usdc, 0UL, 6, nonceValue, false) }
        assertFailsWith<IllegalArgumentException> { SolanaSplTransfer.message(payer, recipient, usdc, 1UL, 19, nonceValue, false) }
        assertFailsWith<IllegalArgumentException> { SolanaSplTransfer.message(payer, recipient, usdc.copyOf(31), 1UL, 6, nonceValue, false) }
    }

    @Test
    fun lenslyReviewsASendToANewAccountAsAnAdvanceACreateAndATransferChecked() {
        val instructions = review("spl-send-nonce-create-ata").instructions
        assertEquals(3, instructions.size)
        assertIs<SolanaInstruction.AdvanceNonce>(instructions[0])
        assertTrue(assertIs<SolanaInstruction.AssociatedTokenCreate>(instructions[1]).idempotent)
        val transfer = assertIs<SolanaInstruction.TokenTransferChecked>(instructions[2])
        assertEquals(1_234_567UL, transfer.amount)
        assertEquals(6, transfer.decimals)
    }

    @Test
    fun lenslyReviewsASendToAnExistingAccountAsAnAdvanceAndATransferChecked() {
        val instructions = review("spl-send-nonce-existing-ata").instructions
        assertEquals(2, instructions.size)
        assertIs<SolanaInstruction.AdvanceNonce>(instructions[0])
        assertEquals(1_234_567UL, assertIs<SolanaInstruction.TokenTransferChecked>(instructions[1]).amount)
    }

    private fun review(name: String): SolanaRequestReview {
        val request = SolSignRequest(
            requestId = null,
            signData = SolanaVectors[name],
            derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
            sourceFingerprint = null,
            address = null,
            origin = null,
            signType = SolSignType.TRANSACTION,
        )
        return assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, listOf(payer))).review
    }

    private companion object {
        const val SENDER_ATA = "EQCnLE6zkPq4HzfnZPkueEABtxdVT5Chk4DgaN9RVCyB"
        const val RECIPIENT_ATA = "Cbaz6s2wbBKnQguE73H2VtGSsjX89W3Xa9cFRHkaKKJE"
    }
}
