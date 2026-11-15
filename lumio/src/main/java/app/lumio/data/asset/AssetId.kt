package app.lumio.data.asset

import app.lumio.data.net.NetworkId

/** A token contract as [AssetId.Erc20] keeps it: 0x and 40 lowercase hex digits. */
private val CONTRACT = Regex("0x[0-9a-f]{40}")

/**
 * What a price or a balance is of: the native coin of a network, or an ERC-20 token on an EVM network, named by its contract. A [Native] asset is
 * the network itself, since each network has one native coin. A token's [Erc20.contract] is lowercase, so two spellings of one contract are one asset.
 */
sealed interface AssetId {
    /** The network the asset lives on. */
    val net: NetworkId

    /** The native coin of [net]. */
    data class Native(override val net: NetworkId) : AssetId

    /** The ERC-20 token at [contract] on [net]. Use [of] for an address as the owner typed it. */
    data class Erc20(override val net: NetworkId, val contract: String) : AssetId {
        init {
            require(CONTRACT.matches(contract)) { "A token contract is 0x and 40 lowercase hex digits." }
        }

        companion object {
            /** The token at [address] on [net], with the address trimmed and lowercased. Throws IllegalArgumentException for text that is not an address. */
            fun of(net: NetworkId, address: String): Erc20 = Erc20(net, address.trim().lowercase())
        }
    }
}
