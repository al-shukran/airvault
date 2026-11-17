package app.lensly.app.sign

import fr.acinq.bitcoin.Base58
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaRequestCheck
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaWarning
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
import app.lensly.core.wallet.toHex
import app.lensly.ui.components.BannerTone
import app.lensly.ui.sign.solanaWarningTone
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The rows, the sentence and the Review Record of a Solana review, built from messages this file writes. */
class SolanaReviewRowsTest {

    @Test
    fun aSolTransferShowsItsSignerFeePayerAmountAndAccounts() {
        val signData = legacy(
            keys = listOf(key(1), key(9), SYSTEM),
            instructions = listOf(transfer(from = 0, to = 1, lamports = 1_500_000_000L)),
        )
        val review = transaction(signData)
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee payer" to address(1),
                "Program" to SYSTEM_ADDRESS,
                "SOL transfer" to "1.5 SOL",
                "From" to address(1),
                "To" to address(9),
            ),
            solanaRows(review),
        )
        assertEquals("Sends 1.5 SOL to ${address(9)}.", solanaSentence(review))
    }

    /** WI-24: an account loaded from a lookup table shows the table, its index and the note that it cannot be checked offline. */
    @Test
    fun anAccountFromALookupTableShowsTheTableIndexAndTheOfflineNote() {
        val review = transaction(v0FromLookupTable())
        assertEquals(1, review.lookups.size)
        assertEquals("lookup table $TABLE, index 0 (can't be checked offline)", solanaRows(review).toMap()["To"])
    }

    @Test
    fun aRawInstructionShowsItsProgramDataAndAccounts() {
        val signData = legacy(keys = listOf(key(1), key(7)), instructions = listOf(Instruction(1, listOf(0), byteArrayOf(9, 9))))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee payer" to address(1),
                "Unknown program" to address(7),
                "Instruction data" to "0x0909",
                "Accounts" to address(1),
            ),
            solanaRows(transaction(signData)),
        )
    }

    @Test
    fun aMessageShowsItsTextWhenEveryCharacterCanBeReadAndItsBytesOtherwise() {
        assertEquals("hello\nworld", solanaMessageText("hello\nworld".toByteArray()))
        assertEquals("0x0102", solanaMessageText(byteArrayOf(1, 2)))
        // A right-to-left override is a format character, so the message shows its bytes rather than text that could mislead.
        assertEquals("0xe280ae", solanaMessageText("‮".toByteArray()))
    }

    @Test
    fun aMessageToSignShowsTheSignerAndTheMessage() {
        val review = review(request("hello".toByteArray(), signType = SolSignType.MESSAGE))
        assertEquals(listOf("Signer" to address(1), "Message" to "hello"), solanaRows(review))
        assertEquals("Signs a message as text.", solanaSentence(review))
    }

    @Test
    fun theRecordOfASignedTransactionNamesSolanaTheSignerAndTheHashOfTheBytes() {
        val signData = legacy(keys = listOf(key(1), key(9), SYSTEM), instructions = listOf(transfer(from = 0, to = 1, lamports = 5L)))
        val review = transaction(signData)

        val record = solanaReviewRecordOf(SolanaReviewEntry(WALLET, ReviewOutcome.SIGNED, request(signData), review, null), atMs = 1L)

        assertEquals(ReviewKind.TRANSACTION, record.kind)
        assertEquals(SOLANA_NETWORK, record.networkName)
        assertEquals(address(1), record.address)
        assertEquals(0, record.accountIndex)
        assertEquals(solanaRows(review), record.params)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(signData).toHex(), record.dataHash)
    }

    /** A review longer than the history's row limit keeps its first rows, and its record says how many it left out. */
    @Test
    fun aRecordKeepsAtMostTwoHundredRowsAndSaysWhatItLeftOut() {
        val signData = legacy(keys = listOf(key(1), key(7)), instructions = List(100) { Instruction(1, emptyList(), ByteArray(0)) })
        val review = transaction(signData)
        assertTrue(solanaRows(review).size > 200)

        val record = solanaReviewRecordOf(SolanaReviewEntry(WALLET, ReviewOutcome.SIGNED, request(signData), review, null), atMs = 1L)

        assertEquals(200, record.params.size)
        assertEquals("More", record.params.last().first)
        assertContains(record.params.last().second, "rows not kept")
    }

    @Test
    fun aRefusedRequestRecordsItsReasonAndNoReview() {
        val record = solanaReviewRecordOf(
            SolanaReviewEntry(WALLET, ReviewOutcome.REFUSED, request(ByteArray(4) { 1 }), reviewed = null, refusal = "Unknown account"),
            atMs = 1L,
        )
        assertEquals("Unknown account", record.refusal)
        assertNull(record.address)
        assertEquals(emptyList(), record.params)
    }

    /** A durable nonce: the advance names the nonce account and its authority, and nothing in the review says the blockhash expires. */
    @Test
    fun aDurableNonceTransferNamesTheNonceAndShowsNoExpiry() {
        val review = transaction(nonceTransfer(authority = 0))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee payer" to address(1),
                "Program" to SYSTEM_ADDRESS,
                "Uses durable nonce account" to address(2),
                "Nonce authority" to address(1),
                "Program" to SYSTEM_ADDRESS,
                "SOL transfer" to "1.5 SOL",
                "From" to address(1),
                "To" to address(9),
            ),
            solanaRows(review),
        )
        assertEquals("Sends 1.5 SOL to ${address(9)}.", solanaSentence(review))
        val shown = solanaRows(review).flatMap { listOf(it.first, it.second) } + solanaSentence(review) +
            solanaFacts(review).flatMap { listOf(it.first, it.second) }
        assertTrue(shown.none { it.contains("expire", ignoreCase = true) || it.contains("blockhash", ignoreCase = true) })
        assertEquals(emptyList(), review.warnings)
    }

    /** The one-time setup shows its account, its rent in SOL and its authority, and the facts count both of its instructions. */
    @Test
    fun aNonceAccountSetupShowsTheAccountRentAndAuthority() {
        val review = transaction(nonceSetup(authority = key(1)))
        assertEquals("Create a nonce account ${address(2)}, rent 0.00144768 SOL, authority ${address(1)}.", solanaSentence(review))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee payer" to address(1),
                "Program" to SYSTEM_ADDRESS,
                "Create a nonce account" to address(2),
                "Rent" to "0.00144768 SOL",
                "Nonce authority" to address(1),
            ),
            solanaRows(review),
        )
        assertEquals("2", solanaFacts(review).toMap()["Instructions"])
        assertEquals(emptyList(), review.warnings)
    }

    /** A nonce authority that is not the signing account is an error-level warning, and the review still shows. */
    @Test
    fun aNonceAuthorityThatIsNotTheSignerWarnsAtErrorLevel() {
        assertEquals(listOf(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER), transaction(nonceTransfer(authority = 1)).warnings)
        assertEquals(listOf(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER), transaction(nonceSetup(authority = key(9))).warnings)
        assertEquals(BannerTone.Error, solanaWarningTone(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER))
        assertContains(solanaWarningText(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER), "Nonce authority is not this account")
    }

    /** Key 0 signs and pays, key 1 is the recipient and key 2 the nonce account. The advance is first, then 1.5 SOL from 0 to 1. */
    private fun nonceTransfer(authority: Int): ByteArray = legacy(
        keys = listOf(key(1), key(9), key(2), SYSTEM, RECENT_BLOCKHASHES),
        instructions = listOf(
            // AdvanceNonceAccount: accounts [nonce, RecentBlockhashes sysvar, authority]; data u32 LE 4.
            Instruction(program = 3, accounts = listOf(2, 4, authority), data = le32(4)),
            // Transfer: accounts [from, to]; data u32 LE 2, then u64 LE lamports.
            Instruction(program = 3, accounts = listOf(0, 1), data = le32(2) + le64(1_500_000_000L)),
        ),
        readonly = 2,
    )

    /** Key 1 signs and pays, and key 2 is the new nonce account, which signs too. The InitializeNonceAccount names [authority]. */
    private fun nonceSetup(authority: ByteArray): ByteArray = legacy(
        keys = listOf(key(1), key(2), SYSTEM, RECENT_BLOCKHASHES, RENT),
        instructions = listOf(
            // CreateAccount: accounts [funder, new account]; data u32 LE 0, u64 LE lamports, u64 LE space 80, the 32-byte owner.
            Instruction(program = 2, accounts = listOf(0, 1), data = le32(0) + le64(1_447_680L) + le64(80L) + ByteArray(32)),
            // InitializeNonceAccount: accounts [nonce, RecentBlockhashes sysvar, Rent sysvar]; data u32 LE 6, then the authority.
            Instruction(program = 2, accounts = listOf(1, 3, 4), data = le32(6) + authority),
        ),
        signers = 2,
        readonly = 3,
    )

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun transaction(signData: ByteArray): SolanaRequestReview = review(request(signData))

    /** The review of [request] for the wallet's one account, key 1, which is the signer. */
    private fun review(request: SolSignRequest): SolanaRequestReview =
        assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, listOf(key(1)))).review

    private fun request(signData: ByteArray, signType: SolSignType = SolSignType.TRANSACTION) = SolSignRequest(
        requestId = ByteArray(16) { 0x22 },
        signData = signData,
        derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
        sourceFingerprint = 1,
        address = null,
        origin = null,
        signType = signType,
    )

    private fun key(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

    private fun address(seed: Int): String = SolanaAccount.addressOf(key(seed))

    private class Instruction(val program: Int, val accounts: List<Int>, val data: ByteArray)

    private fun transfer(from: Int, to: Int, lamports: Long): Instruction = Instruction(
        program = 2,
        accounts = listOf(from, to),
        data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(lamports).array(),
    )

    /** A legacy message: the first [signers] keys sign, the last [readonly] keys are read-only. Every count is below 128. */
    private fun legacy(keys: List<ByteArray>, instructions: List<Instruction>, signers: Int = 1, readonly: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(signers.toByte(), 0, readonly.toByte()))
        out.write(keys.size)
        keys.forEach { out.write(it) }
        out.write(ByteArray(32) { 7 })
        out.write(instructions.size)
        instructions.forEach { instruction ->
            out.write(instruction.program)
            out.write(instruction.accounts.size)
            out.write(instruction.accounts.map { it.toByte() }.toByteArray())
            out.write(instruction.data.size)
            out.write(instruction.data)
        }
        return out.toByteArray()
    }

    /** A v0 message: a System transfer from account 0 to account 2, where account 2 is loaded from [TABLE] at index 0. */
    private fun v0FromLookupTable(): ByteArray {
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(1_000L).array()
        val out = ByteArrayOutputStream()
        out.write(0x80)
        out.write(byteArrayOf(1, 0, 1))
        out.write(2)
        out.write(key(1))
        out.write(SYSTEM)
        out.write(ByteArray(32) { 7 })
        out.write(1)
        out.write(1)
        out.write(2)
        out.write(byteArrayOf(0, 2))
        out.write(data.size)
        out.write(data)
        out.write(1)
        out.write(Base58.decode(TABLE))
        out.write(1)
        out.write(0)
        out.write(0)
        return out.toByteArray()
    }

    private companion object {
        const val WALLET = "w1"
        const val TABLE = "CUDp5gcuAm64fAXUdWviywAdmrnTbrMbFPmP5u7tUL8q"
        const val SYSTEM_ADDRESS = "11111111111111111111111111111111"
        val SYSTEM: ByteArray = Base58.decode(SYSTEM_ADDRESS)
        const val RECENT_BLOCKHASHES_ADDRESS = "SysvarRecentB1ockHashes11111111111111111111"
        const val RENT_ADDRESS = "SysvarRent111111111111111111111111111111111"
        val RECENT_BLOCKHASHES: ByteArray = Base58.decode(RECENT_BLOCKHASHES_ADDRESS)
        val RENT: ByteArray = Base58.decode(RENT_ADDRESS)
    }
}
