package app.lensly.app.sign

import app.lensly.core.solana.SolanaRequestCheck
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaTokenList
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
import fr.acinq.bitcoin.Base58
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/** The rows, sentence and facts of the SPL vectors, as the Solana review shows them. */
class SolanaSplRowsTest {

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

        assertEquals("Sends 2 USDC to token account ${SolanaSplFixtures.DESTINATION}.", solanaSentence(review))
    }

    @Test
    fun aTransferCheckedWithAKnownTokenShowsItsNameSymbolAndMint() {
        assertEquals(
            listOf(
                "Signer" to payer,
                "Fee payer" to payer,
                "Program" to computeBudget,
                "Compute limit" to "300000 units",
                "Program" to computeBudget,
                "Compute price" to "25000 micro-lamports per unit",
                "Program" to token,
                "Token transfer" to "2 USDC",
                "Token" to "USD Coin (USDC)",
                "Mint" to SolanaSplFixtures.USDC,
                "From token account" to SolanaSplFixtures.SOURCE,
                "To token account" to SolanaSplFixtures.DESTINATION,
                "Authority" to payer,
            ),
            solanaRows(review("spl-compute-transfer-checked", usdcTokens)),
        )
    }

    @Test
    fun anUnlistedMintShowsItsAmountInUnitsOfAnUnknownTokenAndNoName() {
        val rows = solanaRows(review("spl-compute-transfer-checked"))

        assertEquals("Token transfer" to "2 of an unknown token", rows.first { it.first == "Token transfer" })
        assertEquals("Token" to "Unknown token", rows.first { it.first == "Token" })
        assertEquals("Mint" to SolanaSplFixtures.USDC, rows.first { it.first == "Mint" })
    }

    @Test
    fun anAssociatedTokenCreateShowsTheOwnerAndTheAccountItMakes() {
        val rows = solanaRows(review("spl-usdc-first-transfer", usdcTokens))

        assertEquals(
            listOf(
                "Program" to ata,
                "Create token account" to "only if missing",
                "Owner" to SolanaSplFixtures.OWNER,
                "Token" to "USD Coin (USDC)",
                "Mint" to SolanaSplFixtures.USDC,
                "Token account" to SolanaSplFixtures.USDC_ATA,
                "Paid by" to payer,
            ),
            rows.subList(rows.indexOf("Program" to ata), rows.indexOf("Program" to ata) + 7),
        )
    }

    @Test
    fun anAssociatedTokenCreateThenATransferCheckedSaysSoInTheSentenceAndFacts() {
        val review = review("spl-usdc-first-transfer", usdcTokens)

        assertEquals("Sends 1.234567 USDC to token account ${SolanaSplFixtures.USDC_ATA}.", solanaSentence(review))
        assertEquals(
            listOf(
                "Instructions" to "4",
                "Fee payer" to "AWxggjuZRm...2QbW7U",
                "Priority fee" to "200 lamports",
                "Mint" to "EPjFWdd5Au...yTDt1v",
            ),
            solanaFacts(review),
        )
    }

    @Test
    fun aPlainTransferSaysItsDecimalsAreUnknownAndNamesNoMint() {
        val review = review("spl-transfer-plain", usdcTokens)

        assertEquals("Sends 7 base units to token account ${SolanaSplFixtures.DESTINATION}.", solanaSentence(review))
        assertEquals(
            listOf(
                "Signer" to payer,
                "Fee payer" to payer,
                "Program" to token,
                "Token transfer" to "7 base units, decimals unknown",
                "From token account" to SolanaSplFixtures.PLAIN_SOURCE,
                "To token account" to SolanaSplFixtures.DESTINATION,
                "Authority" to payer,
            ),
            solanaRows(review),
        )
        // A plain Transfer names no mint, so the facts show no mint.
        assertEquals(listOf("Instructions" to "1", "Fee payer" to "AWxggjuZRm...2QbW7U"), solanaFacts(review))
    }

    @Test
    fun anUnknownProgramBesideAKnownTokenIsShownRawWithItsBytes() {
        val rows = solanaRows(review("spl-unknown-program-usdc", usdcTokens))

        assertEquals(
            listOf(
                "Unknown program" to unknownProgram,
                "Instruction data" to "0xdeadbeef",
                "Accounts" to payer,
            ),
            rows.subList(rows.size - 3, rows.size),
        )
    }
}
