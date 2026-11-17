package app.lensly.core.solana

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An SPL token Lensly names: its mint address in base58, its symbol and name, and its decimals. Public data only. */
data class SolanaToken(val mint: String, val symbol: String, val name: String, val decimals: Int)

/**
 * The SPL tokens Lensly names, by mint address. The list is bundled with the app and read offline. A mint the list does not hold is
 * unknown: the review shows its address, and it names no token.
 */
class SolanaTokenList private constructor(private val byMint: Map<String, SolanaToken>) {

    /** The token of [mint], a base58 address, or null when the list does not hold it. */
    fun token(mint: String): SolanaToken? = byMint[mint]

    /** Every token in the list, in the order the file gives them. */
    val tokens: List<SolanaToken> get() = byMint.values.toList()

    companion object {
        /** The largest decimals count a token in the list can have. */
        private const val MAX_DECIMALS = 18

        /** A list with no tokens, so every mint is unknown. */
        val EMPTY = SolanaTokenList(emptyMap())

        /** Other fields of the bundled file are ignored, so the file can carry its source and date. */
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Reads the bundled JSON, `{"tokens": [{"mint", "symbol", "name", "decimals"}, ...]}`. Other fields are ignored. Throws for
         * malformed JSON, for decimals outside 0 to 18, and for a mint that is listed twice.
         */
        fun parse(json: String): SolanaTokenList {
            val file = JSON.decodeFromString<SolanaTokenFile>(json)
            val tokens = file.tokens.map { row ->
                require(row.decimals in 0..MAX_DECIMALS) { "${row.symbol} has ${row.decimals} decimals; the limit is $MAX_DECIMALS" }
                SolanaToken(row.mint, row.symbol, row.name, row.decimals)
            }
            val byMint = tokens.associateBy { it.mint }
            require(byMint.size == tokens.size) { "the token list names a mint twice" }
            return SolanaTokenList(byMint)
        }
    }
}

/** The bundled file: its tokens. */
@Serializable
private class SolanaTokenFile(val tokens: List<SolanaTokenRow>)

/** One token of the bundled file. */
@Serializable
private class SolanaTokenRow(val mint: String, val symbol: String, val name: String, val decimals: Int)
