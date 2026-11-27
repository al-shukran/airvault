package app.lumio.send.solana

import app.airvault.chains.solana.signer.SolanaMessage
import app.lensly.core.solana.SolanaNonce
import app.lensly.core.solana.SolanaPriorityFee
import app.lensly.core.solana.SolanaSplTransfer
import app.lensly.core.solana.SolanaTokenAccounts
import app.lumio.data.provider.SolanaAccountState
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.solana.SOLANA_DEVNET
import fr.acinq.bitcoin.Base58
import org.junit.Test
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The plan of an SPL token send: its rules, and the message it asks Lensly to sign. The message itself is checked byte for byte in `:shared`. */
class SolanaSplSendTest {
    private val account = SolanaSendAccount(index = 0, key = ByteArray(32) { 1 }, sourceFingerprint = 5)
    private val recipient = ByteArray(32) { 2 }
    private val mint = Base58.encode(ByteArray(32) { 3 })
    private val nonce = ByteArray(32) { 4 }
    private val requestId = ByteArray(16) { 7 }
    private val holding = SplBalance(
        account = Base58.encode(ByteArray(32) { 5 }),
        mint = mint,
        program = SolanaTokenAccounts.TOKEN_PROGRAM,
        amount = BigInteger("5000000"),
        decimals = 6,
    )
    private val sol = BigInteger.valueOf(SOLANA_FEE_LAMPORTS + SOLANA_RENT_RESERVE_LAMPORTS)

    private fun plan(
        balance: SplBalance = holding,
        amount: ULong = 1_234_567UL,
        available: BigInteger = sol,
        recipientAccountExists: Boolean = true,
        priority: Boolean = false,
    ): SolanaSendPlan = SolanaSplSend.plan(
        network = SOLANA_DEVNET,
        account = account,
        recipient = recipient,
        balance = balance,
        amount = amount,
        sol = available,
        nonceValue = nonce,
        requestId = requestId,
        recipientAccountExists = recipientAccountExists,
        rentLamports = RENT_165_BYTES,
        priority = priority,
    )

    /**
     * The Priority Fee switch adds SetComputeUnitPrice after the advance. Its fee is the price times the default units of what the send pays for: the
     * advance (3000), the price instruction itself (3000) and the transfer (200000), and the token account (200000) when the send opens one. Lensly's review shows the same fee.
     */
    @Test
    fun thePrioritySwitchChargesTheDefaultUnitsOfItsInstructions() {
        val existing = plan(priority = true, available = sol + BigInteger.valueOf(SolanaPriorityFee.lamports(206_000L)))
        assertEquals(20_600L, existing.priorityFeeLamports)
        assertContentEquals(
            SolanaSplTransfer.message(account.key, recipient, Base58.decode(mint), 1_234_567UL, 6, nonce, createRecipientAccount = false, priority = true),
            existing.message,
        )
        assertEquals(20_600uL, reviewOf(existing, listOf(account.key)).priorityFeeLamports)

        val opened = plan(
            priority = true,
            recipientAccountExists = false,
            available = sol + BigInteger.valueOf(RENT_165_BYTES + SolanaPriorityFee.lamports(406_000L)),
        )
        assertEquals(40_600L, opened.priorityFeeLamports)
        assertEquals(40_600uL, reviewOf(opened, listOf(account.key)).priorityFeeLamports)
    }

    @Test
    fun aSendToAnExistingTokenAccountIsTheTransferOfTheNonceAndSpendsNoRent() {
        val plan = plan()

        assertContentEquals(
            SolanaSplTransfer.message(account.key, recipient, Base58.decode(mint), 1_234_567UL, 6, nonce, createRecipientAccount = false),
            plan.message,
        )
        assertContentEquals(nonce, plan.blockhash)
        assertEquals(0L, plan.lamports)
        assertEquals(SOLANA_FEE_LAMPORTS, plan.feeLamports)
        assertEquals(mint, plan.token?.mint)
        assertEquals(6, plan.token?.decimals)
        assertEquals(1_234_567UL, plan.token?.amount)
        assertNull(plan.window)
    }

    @Test
    fun aSendToANewTokenAccountOpensItAndPaysItsRent() {
        val available = sol + BigInteger.valueOf(RENT_165_BYTES)

        val plan = plan(available = available, recipientAccountExists = false)

        assertContentEquals(
            SolanaSplTransfer.message(account.key, recipient, Base58.decode(mint), 1_234_567UL, 6, nonce, createRecipientAccount = true),
            plan.message,
        )
    }

    @Test
    fun theMessageAdvancesTheNonceThenTransfersCheckedWithTheMintsDecimals() {
        val message = parsed(plan().message)
        val mintKey = Base58.decode(mint)
        val source = Base58.encode(SolanaTokenAccounts.associatedAddress(account.key, mintKey))
        val destination = Base58.encode(SolanaTokenAccounts.associatedAddress(recipient, mintKey))

        assertContentEquals(nonce, message.recentBlockhash)
        assertEquals(listOf(SYSTEM_PROGRAM, SolanaTokenAccounts.TOKEN_PROGRAM), message.instructions.map { message.programOf(it) })
        val advance = message.instructions[0]
        assertEquals(Base58.encode(SolanaNonce.accountOf(account.key)), message.keyOf(advance.accountIndices[0]))
        assertContentEquals(byteArrayOf(4, 0, 0, 0), advance.data)
        val transfer = message.instructions[1]
        assertEquals(listOf(source, mint, destination, account.address), transfer.accountIndices.map { message.keyOf(it) })
        assertContentEquals(TRANSFER_CHECKED, transfer.data)
    }

    @Test
    fun aNewTokenAccountIsCreatedIdempotentlyBeforeTheTransfer() {
        val available = sol + BigInteger.valueOf(RENT_165_BYTES)

        val message = parsed(plan(available = available, recipientAccountExists = false).message)

        assertEquals(
            listOf(SYSTEM_PROGRAM, SolanaTokenAccounts.ASSOCIATED_TOKEN_PROGRAM, SolanaTokenAccounts.TOKEN_PROGRAM),
            message.instructions.map { message.programOf(it) },
        )
        assertContentEquals(byteArrayOf(1), message.instructions[1].data)
        assertContentEquals(TRANSFER_CHECKED, message.instructions[2].data)
    }

    private fun parsed(message: ByteArray): SolanaMessage = assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(message)).message

    private fun SolanaMessage.programOf(instruction: SolanaMessage.CompiledInstruction): String = Base58.encode(accountKeys[instruction.programIdIndex])

    private fun SolanaMessage.keyOf(index: Int): String = Base58.encode(accountKeys[index])

    @Test
    fun theSolMustCoverTheFeeTheReserveAndANewAccountsRent() {
        val short = sol + BigInteger.valueOf(RENT_165_BYTES - 1)

        assertFailsWith<IllegalArgumentException> { plan(available = short, recipientAccountExists = false) }
        assertTrue(plan(available = short, recipientAccountExists = true).message.isNotEmpty())
    }

    @Test
    fun aToken2022BalanceIsShownButNotSent() {
        val refused = assertFailsWith<IllegalArgumentException> {
            plan(balance = holding.copy(program = SPL_2022_PROGRAM))
        }

        assertEquals(SPL_2022_REFUSAL, refused.message)
    }

    @Test
    fun aRecipientAddressHeldByAnotherProgramIsRefusedBeforeSigning() {
        val owned = SolanaAccountState(owner = SolanaTokenAccounts.TOKEN_PROGRAM, data = ByteArray(165))
        val foreign = SolanaAccountState(owner = SYSTEM_PROGRAM, data = ByteArray(0))

        assertEquals(false, SolanaSplSend.recipientAccountExists(null))
        assertEquals(true, SolanaSplSend.recipientAccountExists(owned))
        val refused = assertFailsWith<IllegalArgumentException> { SolanaSplSend.recipientAccountExists(foreign) }
        assertEquals(RECIPIENT_NOT_TOKEN_ACCOUNT, refused.message)
    }

    @Test
    fun anAmountThatIsNotPositiveOrMoreThanTheAccountHoldsIsRefused() {
        assertFailsWith<IllegalArgumentException> { plan(amount = 0UL) }
        assertFailsWith<IllegalArgumentException> { plan(amount = 5_000_001UL) }
        assertTrue(plan(amount = 5_000_000UL).message.isNotEmpty())
    }

    private companion object {
        /** The rent of a token account, which is 165 bytes: 2,039,280 lamports on the network. */
        const val RENT_165_BYTES = 2_039_280L
        const val SPL_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

        /** TransferChecked of 1,234,567 base units at 6 decimals: tag 12, the amount in 8 little-endian bytes, then the decimals. */
        val TRANSFER_CHECKED = byteArrayOf(12, 0x87.toByte(), 0xD6.toByte(), 0x12, 0, 0, 0, 0, 0, 6)
    }
}
