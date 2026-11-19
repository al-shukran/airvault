package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolanaMessage
import app.airvault.chains.solana.signer.SolanaMessageAccount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SolanaInstructionTest {

    private fun decoded(name: String): List<SolanaInstruction> =
        assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(SolanaVectors[name]), name).message.decodeInstructions()

    private fun key(address: String) = SolanaMessageAccount.Static(address)

    @Test
    fun legacySolTransferDecodes() {
        assertEquals(
            listOf(
                SolanaInstruction.SystemTransfer(
                    programId = key(SolanaVectors.SYSTEM),
                    from = key(SolanaVectors.PAYER),
                    to = key(SolanaVectors.RECIPIENT),
                    lamports = 1_500_000_000uL,
                ),
            ),
            decoded("legacy-sol-transfer"),
        )
    }

    @Test
    fun v0SolTransferReadsTheRecipientFromTheLookupTable() {
        assertEquals(
            listOf(
                SolanaInstruction.SystemTransfer(
                    programId = key(SolanaVectors.SYSTEM),
                    from = key(SolanaVectors.PAYER),
                    to = SolanaMessageAccount.Lookup(SolanaVectors.TABLE, 1, writable = true),
                    lamports = 2_500_000uL,
                ),
            ),
            decoded("v0-sol-transfer-alt"),
        )
    }

    @Test
    fun aLoadedProgramAndItsAccountsStayAsLookups() {
        // The transfer's recipient is writable entry 0 of the table; the unknown program reads readonly entry 1.
        assertEquals(
            listOf(
                SolanaInstruction.SystemTransfer(
                    programId = key(SolanaVectors.SYSTEM),
                    from = key(SolanaVectors.PAYER),
                    to = SolanaMessageAccount.Lookup(SolanaVectors.TABLE, 0, writable = true),
                    lamports = 7uL,
                ),
                SolanaInstruction.Raw(
                    programId = key(SolanaVectors.UNKNOWN),
                    accountIndices = listOf(4),
                    accounts = listOf(SolanaMessageAccount.Lookup(SolanaVectors.TABLE, 1, writable = false)),
                    dataHex = "0102",
                    knownProgram = false,
                ),
            ),
            decoded("v0-alt-readonly-raw"),
        )
    }

    @Test
    fun computeBudgetAssociatedTokenCreateAndTransferCheckedDecode() {
        assertEquals(
            listOf(
                SolanaInstruction.SetComputeUnitLimit(key(SolanaVectors.COMPUTE_BUDGET), units = 200_000u),
                SolanaInstruction.SetComputeUnitPrice(key(SolanaVectors.COMPUTE_BUDGET), microLamports = 1_000uL),
                SolanaInstruction.AssociatedTokenCreate(
                    programId = key(SolanaVectors.ATA),
                    payer = key(SolanaVectors.PAYER),
                    account = key(SolanaVectors.ATA_DST),
                    owner = key(SolanaVectors.OWNER),
                    mint = key(SolanaVectors.MINT),
                    idempotent = true,
                ),
                SolanaInstruction.TokenTransferChecked(
                    programId = key(SolanaVectors.TOKEN),
                    source = key(SolanaVectors.SOURCE),
                    mint = key(SolanaVectors.MINT),
                    destination = key(SolanaVectors.ATA_DST),
                    authority = key(SolanaVectors.PAYER),
                    amount = 1_234_567uL,
                    decimals = 6,
                ),
            ),
            decoded("legacy-spl-checked-ata-compute"),
        )
    }

    @Test
    fun token2022TransfersAndANonIdempotentCreateDecode() {
        assertEquals(
            listOf(
                SolanaInstruction.AssociatedTokenCreate(
                    programId = key(SolanaVectors.ATA),
                    payer = key(SolanaVectors.PAYER),
                    account = key(SolanaVectors.ATA22),
                    owner = key(SolanaVectors.OWNER22),
                    mint = key(SolanaVectors.MINT22),
                    idempotent = false,
                ),
                SolanaInstruction.TokenTransferChecked(
                    programId = key(SolanaVectors.TOKEN_2022),
                    source = key(SolanaVectors.SOURCE),
                    mint = key(SolanaVectors.MINT22),
                    destination = key(SolanaVectors.ATA22),
                    authority = key(SolanaVectors.PAYER),
                    amount = 42uL,
                    decimals = 9,
                ),
                SolanaInstruction.TokenTransfer(
                    programId = key(SolanaVectors.TOKEN),
                    source = key(SolanaVectors.SRC2),
                    destination = key(SolanaVectors.DST2),
                    authority = key(SolanaVectors.PAYER),
                    amount = 7uL,
                ),
            ),
            decoded("v0-token-programs"),
        )
    }

    @Test
    fun anUnknownProgramIsRawWithItsAccountsAndData() {
        assertEquals(
            listOf(
                SolanaInstruction.SystemTransfer(
                    programId = key(SolanaVectors.SYSTEM),
                    from = key(SolanaVectors.PAYER),
                    to = key(SolanaVectors.RECIPIENT),
                    lamports = 5uL,
                ),
                SolanaInstruction.Raw(
                    programId = key(SolanaVectors.UNKNOWN),
                    accountIndices = listOf(0, 1),
                    accounts = listOf(key(SolanaVectors.PAYER), key(SolanaVectors.RECIPIENT)),
                    dataHex = "deadbeef0001",
                    knownProgram = false,
                ),
            ),
            decoded("legacy-unknown-program"),
        )
    }

    @Test
    fun aMessageAtTheSizeLimitDecodesItsRawInstruction() {
        val instructions = decoded("legacy-max-size")
        assertEquals(2, instructions.size)
        val raw = assertIs<SolanaInstruction.Raw>(instructions[1])
        assertEquals(key(SolanaVectors.UNKNOWN), raw.programId)
        assertEquals(1045 * 2, raw.dataHex.length)
    }

    @Test
    fun aKnownProgramWithAnotherShapeStaysRaw() {
        // Built directly, because the decoder reads only the known shapes. Keys: payer, recipient, and the System program, which is
        // 32 zero bytes.
        val payer = ByteArray(32) { 1 }
        val recipient = ByteArray(32) { 2 }
        fun decodeOne(accounts: List<Int>, data: ByteArray): SolanaInstruction = SolanaMessage(
            version = SolanaMessage.Version.LEGACY,
            header = SolanaMessage.Header(1, 0, 1),
            accountKeys = listOf(payer, recipient, ByteArray(32)),
            recentBlockhash = ByteArray(32),
            instructions = listOf(SolanaMessage.CompiledInstruction(2, accounts, data)),
            addressTableLookups = emptyList(),
        ).decodeInstructions().single()

        val transfer = byteArrayOf(2, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0)
        // The transfer shape: two accounts, twelve bytes, tag 2.
        assertEquals(
            SolanaInstruction.SystemTransfer(
                programId = key(SolanaVectors.SYSTEM),
                from = key(fr.acinq.bitcoin.Base58.encode(payer)),
                to = key(fr.acinq.bitcoin.Base58.encode(recipient)),
                lamports = 5uL,
            ),
            decodeOne(listOf(0, 1), transfer),
        )
        // A transfer with a third account is not the transfer shape.
        assertIs<SolanaInstruction.Raw>(decodeOne(listOf(0, 1, 0), transfer))
        // A transfer with 11 bytes of data is not the transfer shape.
        assertIs<SolanaInstruction.Raw>(decodeOne(listOf(0, 1), transfer.copyOf(11)))
        // Tag 1 (CreateAccount) is another instruction, so it is not read as a transfer.
        assertIs<SolanaInstruction.Raw>(decodeOne(listOf(0, 1), byteArrayOf(1, 0, 0, 0) + ByteArray(8)))
    }
}
