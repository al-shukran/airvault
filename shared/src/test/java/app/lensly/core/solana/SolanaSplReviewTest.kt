package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaMessage
import app.airvault.chains.solana.signer.SolanaMessageAccount
import app.lensly.core.ur.PathStep
import fr.acinq.bitcoin.Base58
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The SPL token, Token-2022, associated token account and ComputeBudget shapes, read from the vectors in vectors/solana. The check
 * runs for account 0, whose key is the fee payer of every vector here.
 */
class SolanaSplReviewTest {

    private val payer = Base58.decode(SolanaVectors.PAYER)

    /** The token list of the USDC mint, as the bundled list holds it. */
    private val known = SolanaTokenList.parse(
        """{"tokens": [{"mint": "${SolanaVectors.USDC}", "symbol": "USDC", "name": "USD Coin", "decimals": 6}]}""",
    )

    private fun key(address: String) = SolanaMessageAccount.Static(address)

    private fun review(name: String, tokens: SolanaTokenList = SolanaTokenList.EMPTY): SolanaRequestReview {
        val request = SolSignRequest(
            requestId = null,
            signData = SolanaVectors[name],
            derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
            sourceFingerprint = null,
            address = null,
            origin = null,
            signType = SolSignType.TRANSACTION,
        )
        return assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, listOf(payer), tokens)).review
    }

    @Test
    fun aPlainTransferNamesNoMintAndWarnsThatItsDecimalsAreUnknown() {
        val review = review("spl-transfer-plain")

        assertEquals(
            listOf(
                SolanaInstruction.TokenTransfer(
                    programId = key(SolanaVectors.TOKEN),
                    source = key(SolanaVectors.SRC2),
                    destination = key(SolanaVectors.DST2),
                    authority = key(SolanaVectors.PAYER),
                    amount = 7uL,
                ),
            ),
            review.instructions,
        )
        assertEquals(listOf(SolanaWarning.DECIMALS_UNKNOWN), review.warnings)
        assertEquals(0uL, review.priorityFeeLamports)
    }

    @Test
    fun aTransferCheckedWithComputeBudgetPricesThePriorityFeeAndNamesAKnownToken() {
        val review = review("spl-compute-transfer-checked", tokens = known)

        assertEquals(
            listOf(
                SolanaInstruction.SetComputeUnitLimit(key(SolanaVectors.COMPUTE_BUDGET), units = 300_000u),
                SolanaInstruction.SetComputeUnitPrice(key(SolanaVectors.COMPUTE_BUDGET), microLamports = 25_000uL),
                SolanaInstruction.TokenTransferChecked(
                    programId = key(SolanaVectors.TOKEN),
                    source = key(SolanaVectors.SOURCE),
                    mint = key(SolanaVectors.USDC),
                    destination = key(SolanaVectors.DST2),
                    authority = key(SolanaVectors.PAYER),
                    amount = 2_000_000uL,
                    decimals = 6,
                ),
            ),
            review.instructions,
        )
        // 25,000 micro-lamports times 300,000 units is 7,500 lamports exactly.
        assertEquals(7_500uL, review.priorityFeeLamports)
        assertEquals(SolanaToken(SolanaVectors.USDC, "USDC", "USD Coin", 6), review.tokenOf(key(SolanaVectors.USDC)))
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun anAssociatedTokenCreateThenATransferCheckedShowsTheOwnerAndTheRoundedFee() {
        val review = review("spl-usdc-first-transfer", tokens = known)

        assertEquals(
            listOf(
                SolanaInstruction.SetComputeUnitLimit(key(SolanaVectors.COMPUTE_BUDGET), units = 200_000u),
                SolanaInstruction.SetComputeUnitPrice(key(SolanaVectors.COMPUTE_BUDGET), microLamports = 1_000uL),
                SolanaInstruction.AssociatedTokenCreate(
                    programId = key(SolanaVectors.ATA),
                    payer = key(SolanaVectors.PAYER),
                    account = key(SolanaVectors.USDC_ATA),
                    owner = key(SolanaVectors.OWNER),
                    mint = key(SolanaVectors.USDC),
                    idempotent = true,
                ),
                SolanaInstruction.TokenTransferChecked(
                    programId = key(SolanaVectors.TOKEN),
                    source = key(SolanaVectors.SOURCE),
                    mint = key(SolanaVectors.USDC),
                    destination = key(SolanaVectors.USDC_ATA),
                    authority = key(SolanaVectors.PAYER),
                    amount = 1_234_567uL,
                    decimals = 6,
                ),
            ),
            review.instructions,
        )
        assertEquals(200uL, review.priorityFeeLamports)
        assertEquals(listOf(key(SolanaVectors.USDC)), review.mints)
        assertEquals(emptyList(), review.warnings)
    }

    @Test
    fun aToken2022TransferCheckedOfAnUnlistedMintWarnsThatTheTokenIsUnknown() {
        val review = review("spl-token2022-transfer-checked")

        assertEquals(
            listOf(
                SolanaInstruction.TokenTransferChecked(
                    programId = key(SolanaVectors.TOKEN_2022),
                    source = key(SolanaVectors.SOURCE),
                    mint = key(SolanaVectors.MINT22),
                    destination = key(SolanaVectors.ATA22),
                    authority = key(SolanaVectors.PAYER),
                    amount = 42uL,
                    decimals = 9,
                ),
            ),
            review.instructions,
        )
        assertNull(review.tokenOf(key(SolanaVectors.MINT22)))
        assertEquals(listOf(SolanaWarning.UNKNOWN_TOKEN), review.warnings)
    }

    @Test
    fun anUnknownProgramBesideAKnownTokenWarnsAndIsShownRaw() {
        val review = review("spl-unknown-program-usdc", tokens = known)

        assertEquals(
            listOf(
                SolanaInstruction.TokenTransferChecked(
                    programId = key(SolanaVectors.TOKEN),
                    source = key(SolanaVectors.SOURCE),
                    mint = key(SolanaVectors.USDC),
                    destination = key(SolanaVectors.USDC_ATA),
                    authority = key(SolanaVectors.PAYER),
                    amount = 1_234_567uL,
                    decimals = 6,
                ),
                SolanaInstruction.Raw(
                    programId = key(SolanaVectors.UNKNOWN),
                    accountIndices = listOf(0),
                    accounts = listOf(key(SolanaVectors.PAYER)),
                    dataHex = "deadbeef",
                    knownProgram = false,
                ),
            ),
            review.instructions,
        )
        assertEquals(listOf(SolanaWarning.UNKNOWN_PROGRAM), review.warnings)
    }

    @Test
    fun aTransferCheckedWithATransferHookAccountIsNotTheFourAccountShapeAndStaysRaw() {
        // Token-2022 transfer hooks add accounts. A TransferChecked with five accounts is not the shape Lensly reads, so it is raw.
        val keys = listOf(
            payer,
            Base58.decode(SolanaVectors.SOURCE),
            Base58.decode(SolanaVectors.ATA22),
            Base58.decode(SolanaVectors.MINT22),
            Base58.decode(SolanaVectors.TOKEN_2022),
            Base58.decode(SolanaVectors.UNKNOWN),
        )
        val data = byteArrayOf(12) + ByteArray(8).also { it[0] = 42 } + byteArrayOf(9)
        val message = SolanaMessage(
            version = SolanaMessage.Version.LEGACY,
            header = SolanaMessage.Header(numRequiredSignatures = 1, numReadonlySignedAccounts = 0, numReadonlyUnsignedAccounts = 3),
            accountKeys = keys,
            recentBlockhash = ByteArray(32),
            instructions = listOf(SolanaMessage.CompiledInstruction(programIdIndex = 4, accountIndices = listOf(1, 3, 2, 0, 5), data = data)),
            addressTableLookups = emptyList(),
        )

        val instruction = message.decodeInstructions().single()

        assertIs<SolanaInstruction.Raw>(instruction)
        assertEquals(key(SolanaVectors.TOKEN_2022), instruction.programId)
    }

    @Test
    fun thePriorityFeeRoundsUpToAWholeLamportAndCapsAtTheLargestU64() {
        // The cases of get_prioritization_fee in Agave's compute-budget crate: ceiling division by 10^6, capped at u64::MAX.
        assertEquals(0uL, priorityFee(units = 0u, microLamportsPerUnit = 5uL))
        assertEquals(0uL, priorityFee(units = UInt.MAX_VALUE, microLamportsPerUnit = 0uL))
        assertEquals(1uL, priorityFee(units = 1u, microLamportsPerUnit = 999_999uL))
        assertEquals(1uL, priorityFee(units = 1u, microLamportsPerUnit = 1_000_000uL))
        assertEquals(2uL, priorityFee(units = 1u, microLamportsPerUnit = 1_000_001uL))
        assertEquals(20uL, priorityFee(units = 100_000u, microLamportsPerUnit = 200uL))
        assertEquals(ULong.MAX_VALUE, priorityFee(units = UInt.MAX_VALUE, microLamportsPerUnit = ULong.MAX_VALUE))
    }
}
