package app.lensly.app.sign

import fr.acinq.bitcoin.Base58
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaRequestCheck
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaWarning
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolCluster
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
import app.lensly.core.wallet.toHex
import app.lensly.ui.components.BannerTone
import app.lensly.ui.sign.ReviewFact
import app.lensly.ui.sign.solanaWarningTone
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                "Fee Payer" to address(1),
                "Recent Blockhash" to BLOCKHASH,
                "Program" to SYSTEM_ADDRESS,
                "SOL Transfer" to "1.5 SOL",
                "From" to address(1),
                "To" to address(9),
            ),
            solanaRows(review),
        )
        assertEquals("Sends 1.5 SOL to ${address(9)}.", solanaSentence(review))
    }

    /**
     * WI-24: an account loaded from a lookup table shows the table, its index and the note that it cannot be checked offline. The
     * instruction here is one Lensly does not read, so the account is shown; a value sent to one is refused instead.
     */
    @Test
    fun anAccountFromALookupTableShowsTheTableIndexAndTheOfflineNote() {
        val review = transaction(v0FromLookupTable(data = le32(9)))
        assertEquals(1, review.lookups.size)
        assertEquals("lookup table $TABLE, index 0 (can't be checked offline)", solanaRows(review).toMap()["Account (writable)"])
        assertContains(review.warnings, SolanaWarning.LOOKUP_TABLE)
    }

    @Test
    fun aRawInstructionShowsItsProgramDataAndAccounts() {
        val signData = legacy(keys = listOf(key(1), key(7)), instructions = listOf(Instruction(1, listOf(0), byteArrayOf(9, 9))))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee Payer" to address(1),
                "Recent Blockhash" to BLOCKHASH,
                "Unknown Program" to address(7),
                "Instruction Data" to "0x0909",
                "Account (signer, writable)" to address(1),
            ),
            solanaRows(transaction(signData)),
        )
    }

    /** R05: a known program's instruction that Lensly does not read is labelled as not decoded, not as an unknown program. */
    @Test
    fun aTokenInstructionThatIsNotReadIsLabelledAsNotDecoded() {
        // SetAuthority is tag 6 of the SPL Token program, which Lensly reads only for Transfer and TransferChecked.
        val signData = legacy(
            keys = listOf(key(1), key(7), TOKEN),
            instructions = listOf(Instruction(program = 2, accounts = listOf(1), data = byteArrayOf(6))),
        )
        assertEquals("Program not decoded: instruction is not read" to TOKEN_ADDRESS, solanaRows(transaction(signData))[3])
    }

    /** R07: a raw instruction's accounts show whether each signs and whether it is writable; a second required signer is named. */
    @Test
    fun aRawInstructionShowsEachAccountsSignerAndWritableRoles() {
        // Keys 0 and 1 sign and are writable. Key 2 is the program the instruction calls, writable as an unsigned key. Key 3 is read-only.
        val signData = legacy(
            keys = listOf(key(1), key(2), key(7), key(8)),
            instructions = listOf(Instruction(program = 2, accounts = listOf(0, 1, 3), data = byteArrayOf(9))),
            signers = 2,
            readonly = 1,
        )
        val rows = solanaRows(transaction(signData))
        assertContains(rows, "Other Required Signer" to address(2))
        assertEquals(
            listOf(
                "Account (signer, writable)" to address(1),
                "Account (signer, writable)" to address(2),
                "Account (read-only)" to address(8),
            ),
            rows.filter { it.first.startsWith("Account") },
        )
    }

    /** R03, the review's tick: an instruction Lensly does not read needs the owner's tick, and a transfer it reads does not. */
    @Test
    fun anInstructionThatIsNotReadNeedsTheUnderstandingTick() {
        val rawReview = transaction(legacy(keys = listOf(key(1), key(7)), instructions = listOf(Instruction(1, listOf(0), byteArrayOf(9)))))
        val transferReview = transaction(legacy(keys = listOf(key(1), key(9), SYSTEM), instructions = listOf(transfer(from = 0, to = 1, lamports = 5L))))
        assertTrue(solanaNeedsUnderstanding(rawReview))
        assertFalse(solanaNeedsUnderstanding(transferReview))
    }

    /** R03: a price with no limit shows a priority fee at the default units, marked as at most, with the base and the most fee. */
    @Test
    fun aPriceWithNoLimitShowsItsPriorityFeeAsUpToWithTheBaseAndMostFee() {
        val signData = legacy(
            keys = listOf(key(1), key(9), SYSTEM, COMPUTE_BUDGET),
            instructions = listOf(
                // SetComputeUnitPrice: tag 3, then a u64 of micro-lamports per unit, 1,000,000 here.
                Instruction(program = 3, accounts = emptyList(), data = byteArrayOf(3) + le64(1_000_000L)),
                transfer(from = 0, to = 1, lamports = 5L),
            ),
            readonly = 2,
        )
        val facts = solanaFacts(transaction(signData)).toMap()
        assertEquals("up to 3000 lamports", facts["Priority Fee"])
        assertEquals("5000 lamports", facts["Base Fee"])
        assertEquals("up to 8000 lamports", facts["Max Fee"])
    }

    /** An off-chain message shows its signer, its application domain and its text, as the owner reads it. */
    @Test
    fun aMessageToSignShowsTheSignerTheApplicationDomainAndTheText() {
        val review = review(request(offchain("hello", domain = key(5)), signType = SolSignType.MESSAGE))
        assertEquals(listOf("Signer" to address(1), "Application Domain" to address(5), "Message" to "hello"), solanaRows(review))
        assertEquals("Signs a message as text.", solanaSentence(review))
    }

    /** A text with line breaks shows every line, so the owner reads all of what is signed. */
    @Test
    fun aMessageWithLineBreaksShowsItsWholeText() {
        val text = "Sign in to Example\nNonce 42"
        val review = review(request(offchain(text, format = 1), signType = SolSignType.MESSAGE))
        assertEquals(text, solanaRows(review).toMap()["Message"])
    }

    @Test
    fun theRecordOfASignedMessageHoldsItsTextAndTheHashOfTheEnvelope() {
        val signData = offchain("hello")
        val review = review(request(signData, signType = SolSignType.MESSAGE))

        val record = solanaReviewRecordOf(
            SolanaReviewEntry(WALLET, ReviewOutcome.SIGNED, request(signData, signType = SolSignType.MESSAGE), review, null),
            atMs = 1L,
        )

        assertEquals(ReviewKind.MESSAGE, record.kind)
        assertContains(record.params, "Message" to "hello")
        assertEquals(MessageDigest.getInstance("SHA-256").digest(signData).toHex(), record.dataHash)
    }

    /** The header's network row names the cluster that Lumio states, and marks it as stated by Lumio. */
    @Test
    fun aRequestForDevnetShowsItsNetworkRowAsStatedByLumio() {
        val review = review(request(offchain("hello"), signType = SolSignType.MESSAGE, cluster = SolCluster.DEVNET))
        assertEquals(ReviewFact(label = "Network", value = "Solana Devnet (stated by Lumio)", mono = false), solanaNetworkFact(review))
    }

    /** A request that names no cluster, as Keystone's does, shows its network row as not stated. */
    @Test
    fun aRequestThatNamesNoClusterShowsItsNetworkRowAsNotStated() {
        val review = review(request(offchain("hello"), signType = SolSignType.MESSAGE))
        assertEquals(ReviewFact(label = "Network", value = "Not stated", mono = false), solanaNetworkFact(review))
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

    /**
     * R06: a durable nonce names its nonce account and authority, shows its recent blockhash, which is the stored nonce, and warns that the
     * transaction has no expiry and stays valid until the nonce advances.
     */
    @Test
    fun aDurableNonceTransferWarnsThatItHasNoExpiry() {
        val review = transaction(nonceTransfer(authority = 0))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee Payer" to address(1),
                "Recent Blockhash" to BLOCKHASH,
                "Program" to SYSTEM_ADDRESS,
                "Uses Durable Nonce Account" to address(2),
                "Nonce Authority" to address(1),
                "Program" to SYSTEM_ADDRESS,
                "SOL Transfer" to "1.5 SOL",
                "From" to address(1),
                "To" to address(9),
            ),
            solanaRows(review),
        )
        assertEquals("Sends 1.5 SOL to ${address(9)}.", solanaSentence(review))
        assertEquals(listOf(SolanaWarning.DURABLE_NONCE), review.warnings)
        assertContains(solanaWarningText(SolanaWarning.DURABLE_NONCE), "no expiry")
    }

    /**
     * The one-time setup shows its account, its rent in SOL, its authority and its payer, and the facts count both of its instructions.
     */
    @Test
    fun aNonceAccountSetupShowsTheAccountRentAndAuthority() {
        val review = transaction(nonceSetup(authority = key(1)))
        assertEquals("Create a nonce account ${address(2)}, rent 0.00144768 SOL, authority ${address(1)}.", solanaSentence(review))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee Payer" to address(1),
                "Other Required Signer" to address(2),
                "Recent Blockhash" to BLOCKHASH,
                "Program" to SYSTEM_ADDRESS,
                "Create a Nonce Account" to address(2),
                "Rent" to "0.00144768 SOL",
                "Nonce Authority" to address(1),
                "Paid by" to address(1),
            ),
            solanaRows(review),
        )
        assertEquals("2", solanaFacts(review).toMap()["Instructions"])
        assertEquals(emptyList(), review.warnings)
    }

    /**
     * A seeded setup shows its base and its seed beside the account it creates. The account is the seeded address of the base: SHA-256 of
     * the base, the seed and the System owner. The payer is the signer, and is also the base.
     */
    @Test
    fun aSeededNonceSetupShowsItsBaseSeedAccountRentAuthorityAndPayer() {
        val account = SolanaAccount.addressOf(seededAccount(key(1), SEED))
        val review = transaction(seededNonceSetup(account))
        assertEquals("Create a nonce account $account, rent 0.00144768 SOL, authority ${address(1)}.", solanaSentence(review))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee Payer" to address(1),
                "Recent Blockhash" to BLOCKHASH,
                "Program" to SYSTEM_ADDRESS,
                "Create a Nonce Account" to account,
                "Base Account" to address(1),
                "Seed" to SEED,
                "Rent" to "0.00144768 SOL",
                "Nonce Authority" to address(1),
                "Paid by" to address(1),
            ),
            solanaRows(review),
        )
        assertEquals(emptyList(), review.warnings)
    }

    /** A nonce authority that is not the signing account is an error-level warning, and the review still shows. */
    @Test
    fun aNonceAuthorityThatIsNotTheSignerWarnsAtErrorLevel() {
        assertEquals(
            listOf(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER, SolanaWarning.DURABLE_NONCE),
            transaction(nonceTransfer(authority = 1)).warnings,
        )
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

    /**
     * The seeded setup for [nonce], where key 1 pays, is the base and is the authority. CreateAccountWithSeed names [SEED] as the seed, and
     * InitializeNonceAccount names key 1.
     */
    private fun seededNonceSetup(nonce: String): ByteArray {
        val seed = SEED.toByteArray()
        return legacy(
            keys = listOf(key(1), Base58.decode(nonce), SYSTEM, RECENT_BLOCKHASHES, RENT),
            instructions = listOf(
                // CreateAccountWithSeed: accounts [funder, new account, base]; data u32 LE 3, the 32-byte base, the seed as a u64 LE length
                // and its bytes, u64 LE lamports, u64 LE space 80, then the 32-byte owner.
                Instruction(
                    program = 2,
                    accounts = listOf(0, 1, 0),
                    data = le32(3) + key(1) + le64(seed.size.toLong()) + seed + le64(1_447_680L) + le64(80L) + ByteArray(32),
                ),
                // InitializeNonceAccount: accounts [nonce, RecentBlockhashes sysvar, Rent sysvar]; data u32 LE 6, then the authority.
                Instruction(program = 2, accounts = listOf(1, 3, 4), data = le32(6) + key(1)),
            ),
            readonly = 3,
        )
    }

    /** The address CreateAccountWithSeed gives: SHA-256 of the 32-byte [base], then the seed bytes, then the 32-byte System owner. */
    private fun seededAccount(base: ByteArray, seed: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(base + seed.toByteArray() + ByteArray(32))

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun transaction(signData: ByteArray): SolanaRequestReview = review(request(signData))

    /** The review of [request] for the wallet's one account, key 1, which is the signer. */
    private fun review(request: SolSignRequest): SolanaRequestReview =
        assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, listOf(key(1)))).review

    private fun request(
        signData: ByteArray,
        signType: SolSignType = SolSignType.TRANSACTION,
        cluster: SolCluster? = null,
    ) = SolSignRequest(
        requestId = ByteArray(16) { 0x22 },
        signData = signData,
        derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
        sourceFingerprint = 1,
        address = null,
        origin = null,
        signType = signType,
        cluster = cluster,
    )

    /** An off-chain message at header version 0 for [text] in [format], naming [signers]: the layout the Solana proposal gives. */
    private fun offchain(
        text: String,
        format: Int = 0,
        domain: ByteArray = key(5),
        signers: List<ByteArray> = listOf(key(1)),
    ): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte()))
        out.write("solana offchain".toByteArray(Charsets.US_ASCII))
        out.write(0)
        out.write(domain)
        out.write(format)
        out.write(signers.size)
        signers.forEach { out.write(it) }
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(body.size.toShort()).array())
        out.write(body)
        return out.toByteArray()
    }

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

    /**
     * A v0 message with one System instruction on [data], from account 0 to account 2, where account 2 is loaded from [TABLE] at index 0.
     * The default data is a transfer of 1000 lamports.
     */
    private fun v0FromLookupTable(data: ByteArray = le32(2) + le64(1_000L)): ByteArray {
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
        const val TOKEN_ADDRESS = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val COMPUTE_BUDGET_ADDRESS = "ComputeBudget111111111111111111111111111111"
        val SYSTEM: ByteArray = Base58.decode(SYSTEM_ADDRESS)
        val TOKEN: ByteArray = Base58.decode(TOKEN_ADDRESS)
        val COMPUTE_BUDGET: ByteArray = Base58.decode(COMPUTE_BUDGET_ADDRESS)
        /** The recent blockhash every test message carries: 32 bytes of 7. */
        val BLOCKHASH: String = Base58.encode(ByteArray(32) { 7 })
        const val RECENT_BLOCKHASHES_ADDRESS = "SysvarRecentB1ockHashes11111111111111111111"
        const val RENT_ADDRESS = "SysvarRent111111111111111111111111111111111"
        val RECENT_BLOCKHASHES: ByteArray = Base58.decode(RECENT_BLOCKHASHES_ADDRESS)
        val RENT: ByteArray = Base58.decode(RENT_ADDRESS)

        /** The seed of the seeded nonce account in these tests. */
        const val SEED = "lumio-nonce-0"
    }
}
