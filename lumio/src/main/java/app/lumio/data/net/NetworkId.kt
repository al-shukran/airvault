package app.lumio.data.net

import kotlinx.serialization.Serializable

/** The chain families Lumio reads. */
@Serializable
enum class Family { BTC, EVM, SOL, ALGO }

/**
 * One network: its [family], its [chain] name ("mainnet", "testnet4", or an EVM chain id such as "1"), and whether it is a [testnet]. A
 * provider maps the name to its own API. The registry decides which names exist.
 */
@Serializable
data class NetworkId(val family: Family, val chain: String, val testnet: Boolean) {
    init {
        require(chain.isNotBlank()) { "A network needs a chain name." }
    }
}
