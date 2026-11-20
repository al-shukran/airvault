package app.lensly.app.sign

import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.SolanaTokenList
import app.airvault.chains.solana.signer.toReview
import app.airvault.core.model.Danger
import app.airvault.core.model.unaccounted
import app.lensly.core.ur.PathStep
import fr.acinq.bitcoin.Base58
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The rows, sentence and facts of the SPL vectors, as the Solana review shows them. */
class SolanaSplRowsTest {

    /** The recent blockhash of every SPL fixture message. */
    private val BLOCKHASH = "3K2oo9odsrvknS7gMzJ4eLBnjd3UG14yPuJc8DiviJqY"

    private val computeBudget = "ComputeBudget111111111111111111111111111111"
    private val token = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private val ata = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private val unknownProgram = "9nZkbmWvVmz17UgDtqP4ZDRHzYuTEpPRkFnZVoeMR3pM"
    private val payer = SolanaSplFixtures.PAYER

    /** The list that names USDC, with the name the bundled list gives it. */
    private val usdcTokens = SolanaTokenList.parse(
        """{"tokens": [{"mint": "${SolanaSplFixtures.USDC}", "symbol": "USDC", "name": "USD Coin", "decimals": 6}]}""",
    )

    /** The review of [name] for account 0, checked against [tokens] as the sign flow checks it. */
    private fun review(name: String, tokens: SolanaTokenList = SolanaTokenList.EMPTY): SolanaRequestReview {
        val request = SolSignRequest(
            requestId = null,
            signData = SolanaSplFixtures[name],
            derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
            sourceFingerprint = null,
            address = null,
            origin = null,
            signType = SolSignType.TRANSACTION,
        )
        return assertIs<SolanaRequestCheck.Result.Ok>(
            SolanaRequestCheck.check(request, listOf(Base58.decode(payer)), tokens),
        ).review
    }

    @Test
    fun aTransferCheckedWithAKnownTokenSaysItsSymbolInTheSentence() {
        val review = review("spl-compute-transfer-checked", usdcTokens)

        assertEquals("Send 2 USDC from $payer to ${SolanaSplFixtures.DESTINATION}?", solanaSentence(review))
    }

    @Test
    fun aTransferCheckedWithAKnownTokenShowsItsNameSymbolAndMint() {
        assertEquals(
            listOf(
                "Signer" to payer,
                "Fee Payer" to payer,
                "Recent Blockhash" to BLOCKHASH,
                "Program" to computeBudget,
                "Compute Limit" to "300000 units",
                "Program" to computeBudget,
                "Compute Price" to "25000 micro-lamports per unit",
                "Program" to token,
                "Token Transfer" to "2 USDC",
                "Token" to "USD Coin (USDC)",
                "Mint" to SolanaSplFixtures.USDC,
                "From Token Account" to SolanaSplFixtures.SOURCE,
                "To Token Account" to SolanaSplFixtures.DESTINATION,
                "Authority" to payer,
            ),
            solanaRows(review("spl-compute-transfer-checked", usdcTokens)),
        )
    }

    @Test
    fun anUnlistedMintShowsItsAmountInUnitsOfAnUnknownTokenAndNoName() {
        val rows = solanaRows(review("spl-compute-transfer-checked"))

        assertEquals("Token Transfer" to "2 of an unknown token", rows.first { it.first == "Token Transfer" })
        assertEquals("Token" to "Unknown token", rows.first { it.first == "Token" })
        assertEquals("Mint" to SolanaSplFixtures.USDC, rows.first { it.first == "Mint" })
    }

    @Test
    fun anAssociatedTokenCreateShowsTheOwnerAndTheAccountItMakes() {
        val rows = solanaRows(review("spl-usdc-first-transfer", usdcTokens))

        assertEquals(
            listOf(
                "Program" to ata,
                "Create Token Account" to "only if missing",
                "Owner" to SolanaSplFixtures.OWNER,
                "Token" to "USD Coin (USDC)",
                "Mint" to SolanaSplFixtures.USDC,
                "Token Account" to SolanaSplFixtures.USDC_ATA,
                "Paid by" to payer,
            ),
            rows.subList(rows.indexOf("Program" to ata), rows.indexOf("Program" to ata) + 7),
        )
    }

    @Test
    fun anAssociatedTokenCreateThenATransferCheckedSaysSoInTheSentenceAndFacts() {
        val review = review("spl-usdc-first-transfer", usdcTokens)

        assertEquals("Send 1.234567 USDC from $payer to ${SolanaSplFixtures.USDC_ATA}?", solanaSentence(review))
        assertEquals(
            listOf(
                "Instructions" to "4",
                "Fee Payer" to "AWxggjuZRm...2QbW7U",
                "Base Fee" to "5000 lamports",
                "Priority Fee" to "200 lamports",
                "Max Fee" to "5200 lamports",
                "Mint" to "EPjFWdd5Au...yTDt1v",
            ),
            solanaFacts(review),
        )
    }

    @Test
    fun aPlainTransferSaysItsDecimalsAreUnknownAndNamesNoMint() {
        val review = review("spl-transfer-plain", usdcTokens)

        assertEquals("Send 7 base units from $payer to ${SolanaSplFixtures.DESTINATION}?", solanaSentence(review))
        assertEquals(
            listOf(
                "Signer" to payer,
                "Fee Payer" to payer,
                "Recent Blockhash" to BLOCKHASH,
                "Program" to token,
                "Token Transfer" to "7 base units, decimals unknown",
                "From Token Account" to SolanaSplFixtures.PLAIN_SOURCE,
                "To Token Account" to SolanaSplFixtures.DESTINATION,
                "Authority" to payer,
            ),
            solanaRows(review),
        )
        // A plain Transfer names no mint, so the facts show no mint.
        assertEquals(
            listOf(
                "Instructions" to "1",
                "Fee Payer" to "AWxggjuZRm...2QbW7U",
                "Base Fee" to "5000 lamports",
                "Priority Fee" to "0 lamports",
                "Max Fee" to "5000 lamports",
            ),
            solanaFacts(review),
        )
    }

    @Test
    fun anUnknownProgramBesideAKnownTokenIsShownRawWithItsBytes() {
        val rows = solanaRows(review("spl-unknown-program-usdc", usdcTokens))

        assertEquals(
            listOf(
                "Unknown Program" to unknownProgram,
                "Instruction Data" to "0xdeadbeef",
                "Account (signer, writable)" to payer,
            ),
            rows.subList(rows.size - 3, rows.size),
        )
    }

    /** Every fixture of the SPL vectors passes the coverage check: each field the parser read is shown by a row, or excluded with its reason. */
    @Test
    fun everySplFixtureShowsEveryFieldItReads() {
        listOf("spl-transfer-plain", "spl-usdc-first-transfer", "spl-compute-transfer-checked", "spl-unknown-program-usdc").forEach { name ->
            val checked = review(name, usdcTokens).toReview()
            assertEquals(emptySet(), checked.unaccounted(), name)
            assertEquals(emptyList(), checked.warnings.filter { it.level == Danger.BLOCK }, name)
        }
    }
}
