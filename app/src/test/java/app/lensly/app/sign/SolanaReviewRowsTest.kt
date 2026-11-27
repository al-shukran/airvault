package app.lensly.app.sign

import app.airvault.chains.solana.signer.SolCluster
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.SolanaWarning
import app.airvault.chains.solana.signer.solanaWarningText
import app.airvault.chains.solana.signer.toReview
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.Danger
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.SigningAccount
import app.lensly.app.export.partsOf
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequestEncoder
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.ReviewFact
import fr.acinq.bitcoin.Base58
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals("Send 1.5 SOL from ${address(1)} to ${address(9)}?", solanaSentence(review))
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

    /** AV-015: a static non-signer key that no instruction uses is in the signed bytes, so the review shows it. */
    @Test
    fun aStaticKeyThatNoInstructionUsesIsShownAsAnUnusedAccount() {
        // Key 2 is writable and unsigned; the transfer touches keys 0, 1 and the program at key 3 only.
        val signData = legacy(
            keys = listOf(key(1), key(9), key(5), SYSTEM),
            instructions = listOf(transfer(from = 0, to = 1, lamports = 5L).let { Instruction(3, it.accounts, it.data) }),
        )
        val rows = solanaRows(transaction(signData))
        assertEquals(listOf("Unused Account" to address(5)), rows.filter { it.first == "Unused Account" })
        assertEquals(emptyList(), solanaRows(transaction(legacy(
            keys = listOf(key(1), key(9), SYSTEM),
            instructions = listOf(transfer(from = 0, to = 1, lamports = 5L)),
        ))).filter { it.first == "Unused Account" })
    }

    /** #1419: a lookup-table slot that no instruction uses is in the signed bytes, so the review shows its table and index. */
    @Test
    fun aLookupSlotThatNoInstructionUsesIsShownAsAnUnusedLookupAccount() {
        // Slots 0 and 5 are loaded as accounts 2 and 3. The instruction uses account 2 only. (Lensly refuses a transfer to a lookup account.)
        val review = transaction(v0FromLookupTable(data = le32(9), writableIndexes = listOf(0, 5)))
        val rows = solanaRows(review)
        assertEquals(
            listOf("Unused Lookup Account" to "lookup table $TABLE, index 5 (can't be checked offline)"),
            rows.filter { it.first.startsWith("Unused") },
        )
        val labels = rows.map { it.first }
        assertTrue(labels.indexOf("Fee Payer") < labels.indexOf("Unused Lookup Account"))
        assertTrue(labels.indexOf("Unused Lookup Account") < labels.indexOf("Recent Blockhash"))
        assertTrue(review.toReview().unaccounted().isEmpty())
        assertEquals(emptyList(), solanaRows(transaction(v0FromLookupTable(data = le32(9)))).filter { it.first.startsWith("Unused") })
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
        assertEquals("up to 6000 lamports", facts["Priority Fee"])
        assertEquals("5000 lamports", facts["Base Fee"])
        assertEquals("up to 11000 lamports", facts["Max Fee"])
    }

    /** #1484: the compute budget instruction is a builtin too, so a price with no limit counts it: 6,000 units here, not 3,000. */
    @Test
    fun aPriceWithNoLimitCountsTheComputeBudgetInstructionAsABuiltin() {
        val signData = legacy(
            keys = listOf(key(1), key(9), SYSTEM, COMPUTE_BUDGET),
            instructions = listOf(
                Instruction(program = 3, accounts = emptyList(), data = byteArrayOf(3) + le64(1_000_000_000_000L)),
                transfer(from = 0, to = 1, lamports = 5L),
            ),
            readonly = 2,
        )
        val review = transaction(signData)
        assertEquals(6_000_000_000uL, review.priorityFeeLamports)
        assertEquals(6_000_005_000uL, review.maxFeeLamports)
    }

    /** A priority fee at the largest u64 gives a maximum fee at that largest u64 too, never a wrapped sum below the priority fee. */
    @Test
    fun aMaximumFeeAtTheLargestPriorityFeeIsCappedNotWrapped() {
        val signData = legacy(
            keys = listOf(key(1), key(9), SYSTEM, COMPUTE_BUDGET),
            instructions = listOf(
                // SetComputeUnitLimit: tag 2, then a u32 of 1,400,000 units. SetComputeUnitPrice: tag 3, then the largest u64.
                Instruction(program = 3, accounts = emptyList(), data = byteArrayOf(2) + le32(1_400_000)),
                Instruction(program = 3, accounts = emptyList(), data = byteArrayOf(3) + le64(-1L)),
                transfer(from = 0, to = 1, lamports = 5L),
            ),
            readonly = 2,
        )
        val review = transaction(signData)
        assertEquals(ULong.MAX_VALUE, review.maxFeeLamports)
        assertEquals("${ULong.MAX_VALUE} lamports", solanaFacts(review).toMap()["Max Fee"])
    }

    /** #1491: a message commits to no cluster, so a cluster the request states never marks the header as a test network. */
    @Test
    fun aClusterTheRequestStatesNeverMarksTheHeaderAsATestnet() {
        for (cluster in listOf(SolCluster.DEVNET, SolCluster.TESTNET, SolCluster.MAINNET_BETA, null)) {
            val tx = review(request(legacy(keys = listOf(key(1), key(9), SYSTEM), instructions = listOf(transfer(0, 1, 5L))), cluster = cluster))
            assertFalse(tx.toReview().network.testnet, "cluster $cluster")
        }
    }

    @Test
    fun aMessageToSignShowsTheSignerTheApplicationDomainAndTheText() {
        val review = review(request(offchain("hello", domain = key(5)), signType = SolSignType.MESSAGE))
        assertEquals(
            listOf("Signer" to address(1), "Application Domain" to address(5), "Format" to "Restricted ASCII", "Message" to "hello"),
            solanaRows(review),
        )
        assertEquals("Signs a message as text.", solanaSentence(review))
    }

    /** Every signer a message names is shown, and the review accounts for each field the parser read. */
    @Test
    fun aMessageWithTwoSignersShowsBothAndAccountsForEveryField() {
        val review = review(request(offchain("hello", format = 1, signers = listOf(key(1), key(2))), signType = SolSignType.MESSAGE))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Other Signer" to address(2),
                "Application Domain" to address(5),
                "Format" to "Limited UTF-8",
                "Message" to "hello",
            ),
            solanaRows(review),
        )
        val checked = review.toReview()
        assertTrue(checked.unaccounted().isEmpty(), checked.unaccounted().toString())
        assertTrue(checked.warnings.none { it.level == Danger.BLOCK })
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
        val sol = request(signData, signType = SolSignType.MESSAGE)

        val record = recordOf(appRequest(sol), review(sol))

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
        val sol = request(signData, origin = "lumio")
        val review = review(sol)

        val record = recordOf(appRequest(sol), review)

        assertEquals(ReviewKind.TRANSACTION, record.kind)
        assertEquals("Solana", record.networkName)
        assertEquals("lumio", record.origin)
        assertEquals(address(1), record.address)
        assertEquals(0, record.accountIndex)
        // The record keeps the header (the cluster and the facts) before the rows the owner read.
        val reviewed = review.toReview()
        val header = (listOfNotNull(reviewed.network.row) + reviewed.facts).map { it.label to it.value.text() }
        assertEquals(header + solanaRows(review), record.params)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(signData).toHex(), record.dataHash)
    }

    /** The Activity detail lists a Solana record's account and the addresses of its rows as Solana addresses. */
    @Test
    fun aSolanaRecordListsItsAccountAndRowAddressesOnSolana() {
        val signData = legacy(keys = listOf(key(1), key(9), SYSTEM), instructions = listOf(transfer(from = 0, to = 1, lamports = 5L)))

        val record = recordOf(appRequest(request(signData)), transaction(signData))

        assertEquals(SOLANA_FAMILY, recordChainOf(record))
        assertContains(recordAddressesOf(record), RecordAddress("Account", address(1), SOLANA_FAMILY))
        assertContains(recordAddressesOf(record), RecordAddress("To", address(9), SOLANA_FAMILY))
    }

    /** A review longer than the history's row limit keeps its first rows, and its record says how many it left out. */
    @Test
    fun aRecordKeepsAtMostTwoHundredRowsAndSaysWhatItLeftOut() {
        val signData = legacy(keys = listOf(key(1), key(7)), instructions = List(100) { Instruction(1, emptyList(), ByteArray(0)) })
        val review = transaction(signData)
        assertTrue(solanaRows(review).size > 200)

        val record = recordOf(appRequest(request(signData)), review)

        assertEquals(200, record.params.size)
        assertEquals("More", record.params.last().first)
        assertContains(record.params.last().second, "rows not kept")
    }

    @Test
    fun aRefusedRequestRecordsItsReasonAndNoReview() {
        val record = recordOf(appRequest(request(ByteArray(4) { 1 })), review = null, outcome = ReviewOutcome.REFUSED, refusal = "Unknown account")

        assertEquals("Solana", record.networkName)
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
        assertEquals("Send 1.5 SOL from ${address(1)} to ${address(9)}?", solanaSentence(review))
        assertEquals(listOf(SolanaWarning.DURABLE_NONCE), review.warnings)
        assertContains(solanaWarningText(SolanaWarning.DURABLE_NONCE), "no expiry")
    }

    /**
     * The one-time setup shows its account, its deposit in SOL, its authority and its payer, and the facts count both of its instructions.
     */
    @Test
    fun aNonceAccountSetupShowsTheAccountDepositAndAuthority() {
        val review = transaction(nonceSetup(authority = key(1)))
        assertEquals("Create a nonce account ${address(2)}, deposit 0.00144768 SOL, authority ${address(1)}.", solanaSentence(review))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee Payer" to address(1),
                "Other Required Signer" to address(2),
                "Recent Blockhash" to BLOCKHASH,
                "Program" to SYSTEM_ADDRESS,
                "Create a Nonce Account" to address(2),
                "Deposit" to "0.00144768 SOL",
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
    fun aSeededNonceSetupShowsItsBaseSeedAccountDepositAuthorityAndPayer() {
        val account = SolanaAccount.addressOf(seededAccount(key(1), SEED))
        val review = transaction(seededNonceSetup(account))
        assertEquals("Create a nonce account $account, deposit 0.00144768 SOL, authority ${address(1)}.", solanaSentence(review))
        assertEquals(
            listOf(
                "Signer" to address(1),
                "Fee Payer" to address(1),
                "Recent Blockhash" to BLOCKHASH,
                "Program" to SYSTEM_ADDRESS,
                "Create a Nonce Account" to account,
                "Base Account" to address(1),
                "Seed" to SEED,
                "Deposit" to "0.00144768 SOL",
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
        assertEquals(Danger.DANGER, transaction(nonceSetup(authority = key(9))).toReview().warnings.single().level)
        assertContains(solanaWarningText(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER), "Nonce authority is not this account")
    }

    /** The nonce authority warning is a DANGER the owner ticks before the review can be signed, as the unknown-program warning is. */
    @Test
    fun aNonceAuthorityThatIsNotTheSignerNeedsItsTick() {
        for (review in listOf(transaction(nonceTransfer(authority = 1)), transaction(nonceSetup(authority = key(9))))) {
            val warning = review.toReview().warnings.single { it.code == "solana.nonce-authority" }
            assertEquals(Danger.DANGER, warning.level)
            assertNotNull(warning.acknowledge)
        }
    }

    /**
     * #1485: a nonce setup that funds an account whose authority is someone else, beside a small send, is not summarised as the small send.
     * Its funds are a deposit, not rent, and the warning says the authority can withdraw them.
     */
    @Test
    fun aNonceSetupBesideASendIsADepositTheAuthorityCanWithdrawAndIsNotSummarisedAsTheSend() {
        val signData = legacy(
            keys = listOf(key(1), key(2), key(3), SYSTEM, RECENT_BLOCKHASHES, RENT),
            instructions = listOf(
                Instruction(program = 3, accounts = listOf(0, 1), data = le32(0) + le64(100_000_000_000L) + le64(80L) + ByteArray(32)),
                Instruction(program = 3, accounts = listOf(1, 4, 5), data = le32(6) + key(9)),
                transfer(from = 0, to = 2, lamports = 1_000_000L, program = 3),
            ),
            signers = 2,
            readonly = 3,
        )
        val review = transaction(signData)
        assertTrue(review.toReview().summary.none { it is SummaryPart.Send }, solanaSentence(review))
        assertFalse(solanaRows(review).any { it.first == "Rent" })
        assertContains(solanaRows(review), "Deposit" to "100 SOL")
        assertContains(solanaWarningText(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER), "withdraw all of its deposit")
        assertEquals(listOf(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER), review.warnings)
    }

    /** #1486: each token and System instruction that spends or hands over control shows rows that name its target, and is not raw hex. */
    @Test
    fun instructionsThatSpendOrHandOverControlAreDecodedNotRaw() {
        // Keys: 0 signer and owner, 1 token account, 2 the other address, 3 mint, 4 Token, 5 System, 6 RecentBlockhashes, 7 Rent.
        fun review(program: Int, accounts: List<Int>, data: ByteArray) = transaction(
            legacy(
                keys = listOf(key(1), key(3), key(9), key(4), TOKEN, SYSTEM, RECENT_BLOCKHASHES, RENT),
                instructions = listOf(Instruction(program, accounts, data)),
                readonly = 4,
            ),
        )
        val cases = listOf(
            // Approve (4): [source, delegate, owner], u64 amount. ApproveChecked (13): [source, mint, delegate, owner], u64 amount, decimals.
            Triple(review(4, listOf(1, 2, 0), byteArrayOf(4) + le64(1000L)), "Delegate" to address(9), true),
            Triple(review(4, listOf(1, 3, 2, 0), byteArrayOf(13) + le64(1000L) + byteArrayOf(6)), "Delegate" to address(9), true),
            // SetAuthority (6): [account, current authority], authority type, flag 1 and the new authority. Flag 0 removes the authority.
            Triple(review(4, listOf(1, 0), byteArrayOf(6, 2, 1) + key(9)), "New Authority" to address(9), true),
            Triple(review(4, listOf(1, 0), byteArrayOf(6, 3, 0)), "New Authority" to "None (the authority is removed for good)", true),
            // CloseAccount (9): [account, destination, owner]. Burn (8): [account, mint, authority]. BurnChecked (15) adds decimals.
            Triple(review(4, listOf(1, 2, 0), byteArrayOf(9)), "Remaining SOL To" to address(9), false),
            Triple(review(4, listOf(1, 3, 0), byteArrayOf(8) + le64(5L)), "Token Burn" to "5 base units, decimals unknown", false),
            Triple(review(4, listOf(1, 3, 0), byteArrayOf(15) + le64(5L) + byteArrayOf(0)), "Token Burn" to "5 of an unknown token", false),
            // WithdrawNonceAccount (5): [nonce, to, RecentBlockhashes, Rent, authority], u64 lamports. AuthorizeNonceAccount (7): [nonce,
            // authority], the new authority. Assign (1): [account], the new owner. CreateAccount (0): [funder, new], lamports, space, owner.
            Triple(review(5, listOf(1, 2, 6, 7, 0), le32(5) + le64(2_000_000_000L)), "Nonce Withdrawal" to "2 SOL", false),
            Triple(review(5, listOf(1, 0), le32(7) + key(9)), "New Nonce Authority" to address(9), true),
            Triple(review(5, listOf(1), le32(1) + key(9)), "New Owner Program" to address(9), true),
            Triple(review(5, listOf(0, 1), le32(0) + le64(3_000_000_000L) + le64(10L) + key(9)), "Deposit" to "3 SOL", false),
        )
        for ((review, row, hasControlWarning) in cases) {
            val rows = solanaRows(review)
            assertContains(rows, row)
            assertTrue(rows.none { it.first == "Unknown Program" || it.first.startsWith("Program not decoded") }, "$row: $rows")
            assertEquals(hasControlWarning, SolanaWarning.HANDS_OVER_CONTROL in review.warnings, "$row")
            assertTrue(SolanaWarning.UNKNOWN_PROGRAM !in review.warnings, "$row")
            val warning = review.toReview().warnings.singleOrNull { it.code == "solana.hands-over-control" }
            assertEquals(hasControlWarning, warning?.acknowledge != null, "$row")
        }
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
        origin: String? = null,
    ) = SolSignRequest(
        requestId = ByteArray(16) { 0x22 },
        signData = signData,
        derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
        sourceFingerprint = 1,
        address = null,
        origin = origin,
        signType = signType,
        cluster = cluster,
    )

    /** The app request of [sol], as the sign flow holds it: the sol-sign-request that carries it, under the Solana app's header. */
    private fun appRequest(sol: SolSignRequest): AppRequest {
        val scanner = UrScanner()
        partsOf(SolSignRequestEncoder.encode(sol)).forEach { assertTrue(scanner.receive(it)) }
        val header = RequestHeader(
            app = solanaManifest.id,
            chain = solanaManifest.networks.first { !it.testnet }.chainId,
            kind = if (sol.signType == SolSignType.MESSAGE) RequestKind.MESSAGE else RequestKind.TRANSACTION,
            signers = listOf(SignerRef(sol.sourceFingerprint, sol.derivationPath)),
            requestId = sol.requestId,
        )
        return AppRequest(header, checkNotNull(scanner.result()).cborBytes)
    }

    /** The Review Record the sign flow writes for [request] with [review], the review the owner saw, or none for a refusal before one. */
    private fun recordOf(
        request: AppRequest,
        review: SolanaRequestReview?,
        outcome: ReviewOutcome = ReviewOutcome.SIGNED,
        refusal: String? = null,
    ): ReviewRecord {
        val reviewed = review?.let {
            AppPrepared.Ready(
                walletId = WALLET,
                request = request,
                review = it.toReview(),
                accountIndex = it.accountIndex,
                signer = SigningAccount(name = "Test 1", walletName = "Test", address = it.signerAddress),
                addressChain = SOLANA_FAMILY,
                counterparties = counterpartiesOf(it.toReview()),
                addressNotes = emptyMap(),
            )
        }
        val (origin, dataHash) = appRequestFactsOf(request)
        return appReviewRecordOf(
            AppReviewEntry(WALLET, outcome, request, reviewed, acknowledged = emptyList(), refusal = refusal),
            atMs = 1L,
            origin = origin,
            dataHash = dataHash,
            displayName = solanaManifest.displayName,
        )
    }

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

    private fun transfer(from: Int, to: Int, lamports: Long, program: Int = 2): Instruction = Instruction(
        program = program,
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
     * The default data is a transfer of 1000 lamports. [writableIndexes] are the table slots the message loads, the first of which is account 2.
     */
    private fun v0FromLookupTable(data: ByteArray = le32(2) + le64(1_000L), writableIndexes: List<Int> = listOf(0)): ByteArray {
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
        out.write(writableIndexes.size)
        writableIndexes.forEach { out.write(it) }
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
