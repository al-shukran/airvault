package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolanaInstruction
import app.airvault.chains.solana.signer.SolanaMessage
import app.airvault.chains.solana.signer.SolanaMessageAccount
import app.airvault.chains.solana.signer.decodeInstructions
import app.lensly.core.wallet.toHex
import fr.acinq.bitcoin.Base58
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.Test

/**
 * The legacy transfer message against fixtures from a second generator. The fixtures were made with @solana/web3.js 1.98.4, in a folder of their
 * own, by `node gen-transfer.js`. The script builds one `SystemProgram.transfer` with the sender as fee payer and `compileMessage().serialize()`.
 * Its two cases are:
 * - "sol-transfer-1-5-sol": 1.5 SOL (1500000000 lamports) from [SolanaVectors.PAYER] to [SolanaVectors.RECIPIENT], blockhash [SolanaVectors.BLOCKHASH].
 *   These are the inputs of the solders vector "legacy-sol-transfer", so that vector is a third source for the same bytes.
 * - "sol-transfer-large": 987654321012 lamports from [SolanaVectors.PAYER] to [SolanaVectors.OWNER], the same blockhash. The amount is above 32 bits.
 */
class SolanaTransferTest {

    private val web3jsSolTransfer = "010001038d65fcf7d4880cd5224b36c33e43617cc519fc6514f79759f65fb571649dffab665d0698dbc8fb95afc25c3a4d9cf280d87a585b7999243ca6008fd03258975f00000000000000000000000000000000000000000000000000000000000000002255df13e0eba62852d0c628db8bc6291d022f2d699a612c231a6472696c973b01020200010c02000000002f685900000000"

    private val web3jsLargeTransfer = "010001038d65fcf7d4880cd5224b36c33e43617cc519fc6514f79759f65fb571649dffab4c1029697ee358715d3a14a2add817c4b01651440de808371f78165ac90dc58100000000000000000000000000000000000000000000000000000000000000002255df13e0eba62852d0c628db8bc6291d022f2d699a612c231a6472696c973b01020200010c0200000074f3c8f4e5000000"

    private fun key(address: String): ByteArray = Base58.decode(address)

    @Test
    fun theOneAndAHalfSolTransferIsTheWeb3jsFixtureByteForByte() {
        val built = SolanaTransfer.message(key(SolanaVectors.PAYER), key(SolanaVectors.RECIPIENT), 1_500_000_000L, key(SolanaVectors.BLOCKHASH))

        assertEquals(web3jsSolTransfer, built.toHex())
    }

    @Test
    fun theSameTransferIsTheSolanaVectorFromSolders() {
        val built = SolanaTransfer.message(key(SolanaVectors.PAYER), key(SolanaVectors.RECIPIENT), 1_500_000_000L, key(SolanaVectors.BLOCKHASH))

        assertContentEquals(SolanaVectors["legacy-sol-transfer"], built)
    }

    @Test
    fun anAmountAbove32BitsIsTheWeb3jsFixtureByteForByte() {
        val built = SolanaTransfer.message(key(SolanaVectors.PAYER), key(SolanaVectors.OWNER), 987_654_321_012L, key(SolanaVectors.BLOCKHASH))

        assertEquals(web3jsLargeTransfer, built.toHex())
    }

    @Test
    fun theMessageParsesBackToTheSameTransfer() {
        val built = SolanaTransfer.message(key(SolanaVectors.PAYER), key(SolanaVectors.OWNER), 987_654_321_012L, key(SolanaVectors.BLOCKHASH))

        val message = assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(built)).message
        assertEquals(SolanaMessage.Version.LEGACY, message.version)
        assertEquals(SolanaVectors.PAYER, message.feePayer)
        assertEquals(SolanaVectors.BLOCKHASH, Base58.encode(message.recentBlockhash))
        assertEquals(listOf(SolanaVectors.PAYER, SolanaVectors.OWNER, SolanaVectors.SYSTEM), message.accountKeys.map { Base58.encode(it) })
        assertEquals(1, message.header.numRequiredSignatures)
        assertEquals(0, message.header.numReadonlySignedAccounts)
        assertEquals(1, message.header.numReadonlyUnsignedAccounts)

        val transfer = assertIs<SolanaInstruction.SystemTransfer>(message.decodeInstructions().single())
        assertEquals(SolanaMessageAccount.Static(SolanaVectors.SYSTEM), transfer.programId)
        assertEquals(SolanaMessageAccount.Static(SolanaVectors.PAYER), transfer.from)
        assertEquals(SolanaMessageAccount.Static(SolanaVectors.OWNER), transfer.to)
        assertEquals(987_654_321_012UL, transfer.lamports)
    }

    @Test
    fun aKeyOrBlockhashOfTheWrongSizeIsRefusedAndANegativeAmountToo() {
        val sender = key(SolanaVectors.PAYER)
        val recipient = key(SolanaVectors.RECIPIENT)
        val blockhash = key(SolanaVectors.BLOCKHASH)

        assertFailsWith<IllegalArgumentException> { SolanaTransfer.message(sender.copyOf(31), recipient, 1L, blockhash) }
        assertFailsWith<IllegalArgumentException> { SolanaTransfer.message(sender, recipient.copyOf(33), 1L, blockhash) }
        assertFailsWith<IllegalArgumentException> { SolanaTransfer.message(sender, recipient, 1L, blockhash.copyOf(31)) }
        assertFailsWith<IllegalArgumentException> { SolanaTransfer.message(sender, recipient, -1L, blockhash) }
    }
}
