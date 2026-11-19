package app.airvault.chains.solana.signer

import fr.acinq.bitcoin.Base58
import kotlin.test.Test
import kotlin.test.assertIs

class SolanaAssociatedTokenCreateTest {
    private val associatedTokenProgram = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private val tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private val token2022Program = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    private val systemProgram = ByteArray(32)

    /** A message with one associated-token create. Accounts 0 to 3 are payer, account, owner and mint; accounts 4 and 5 are [system] and [token]. */
    private fun decode(system: ByteArray, token: ByteArray): SolanaInstruction = SolanaMessage(
        version = SolanaMessage.Version.LEGACY,
        header = SolanaMessage.Header(1, 0, 1),
        accountKeys = listOf(
            ByteArray(32) { 1 },
            ByteArray(32) { 2 },
            ByteArray(32) { 3 },
            ByteArray(32) { 4 },
            system,
            token,
            Base58.decode(associatedTokenProgram),
        ),
        recentBlockhash = ByteArray(32),
        instructions = listOf(SolanaMessage.CompiledInstruction(6, listOf(0, 1, 2, 3, 4, 5), byteArrayOf())),
        addressTableLookups = emptyList(),
    ).decodeInstructions().single()

    @Test
    fun aCreateThatCallsTheSystemAndTokenProgramsIsDecoded() {
        assertIs<SolanaInstruction.AssociatedTokenCreate>(decode(systemProgram, Base58.decode(tokenProgram)))
    }

    @Test
    fun aCreateForToken2022IsDecoded() {
        assertIs<SolanaInstruction.AssociatedTokenCreate>(decode(systemProgram, Base58.decode(token2022Program)))
    }

    @Test
    fun aCreateWhoseSystemProgramIsNotTheSystemProgramIsNotDecoded() {
        // The program the create calls for the account is not the System Program, so the review shows it as an unknown program.
        assertIs<SolanaInstruction.Raw>(decode(ByteArray(32) { 9 }, Base58.decode(tokenProgram)))
    }

    @Test
    fun aCreateWhoseTokenProgramIsNotATokenProgramIsNotDecoded() {
        assertIs<SolanaInstruction.Raw>(decode(systemProgram, ByteArray(32) { 9 }))
    }
}
