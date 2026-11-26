package app.lumio.data.asset

import app.lumio.data.net.NetworkId

/** A token contract as [AssetId.Erc20] keeps it: 0x and 40 lowercase hex digits. */
private val CONTRACT = Regex("0x[0-9a-f]{40}")

/** An SPL mint as [AssetId.Spl] keeps it: a base58 public key, whose letters are case-sensitive. */
private val MINT = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")

/**
 * What a price or a balance is of: the native coin of a network, or a token on it. A token is an ERC-20 on an EVM network named by its contract, an
 * SPL token on Solana named by its mint, or an ASA on Algorand named by its asset ID. A [Native] asset is the network itself, since each network has
 * one native coin. A token's [Erc20.contract] is lowercase, so two spellings of one contract are one asset. A [Spl.mint] keeps its case.
 */
sealed interface AssetId {
    /** The network the asset lives on. */
    val net: NetworkId

    /** The native coin of [net]. */
    data class Native(override val net: NetworkId) : AssetId

    /** A token on [net]. [tokenId] is how a price source names it: the contract, the mint or the asset ID. */
    sealed interface Token : AssetId {
        val tokenId: String
    }

    /** The ERC-20 token at [contract] on [net]. Use [of] for an address as the owner typed it. */
    data class Erc20(override val net: NetworkId, val contract: String) : Token {
        override val tokenId: String get() = contract

        init {
            require(CONTRACT.matches(contract)) { "A token contract is 0x and 40 lowercase hex digits." }
        }

        companion object {
            /** The token at [address] on [net], with the address trimmed and lowercased. Throws IllegalArgumentException for text that is not an address. */
            fun of(net: NetworkId, address: String): Erc20 = Erc20(net, address.trim().lowercase())
        }
    }

    /** The SPL token with [mint] on [net]. A mint is case-sensitive, so it is never lowercased. */
    data class Spl(override val net: NetworkId, val mint: String) : Token {
        override val tokenId: String get() = mint

        init {
            require(net.isSol) { "An SPL token is on a Solana network." }
            require(MINT.matches(mint)) { "A mint is a base58 public key." }
        }
    }

    /** The Algorand Standard Asset with ID [id] on [net]. */
    data class Asa(override val net: NetworkId, val id: ULong) : Token {
        override val tokenId: String get() = id.toString()

        init {
            require(net.isAlgo) { "An ASA is on an Algorand network." }
            require(id > 0UL) { "An asset ID is above zero." }
        }
    }

    companion object {
        /**
         * The token that [tokenId] names on [net], as a watchlist or a route keeps it: a contract on an EVM network, a mint on Solana, an asset ID on
         * Algorand. Throws IllegalArgumentException when [tokenId] is not one.
         */
        fun token(net: NetworkId, tokenId: String): Token = when {
            net.isSol -> Spl(net, tokenId)
            net.isAlgo -> Asa(net, requireNotNull(tokenId.toULongOrNull()) { "An asset ID is a number." })
            else -> Erc20(net, tokenId)
        }
    }
}
