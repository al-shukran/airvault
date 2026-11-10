package app.lensly.core.registry

import kotlinx.serialization.Serializable

private val CONTRACT_PATTERN = Regex("0x[0-9a-f]{40}")

/** One ERC-20 token the vault will display and transfer. Decimals come only from here. */
@Serializable
data class TokenEntry(
    val chainId: Long,
    /** Lowercase 0x-prefixed 20-byte address. */
    val contract: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
) {
    init {
        require(CONTRACT_PATTERN.matches(contract)) {
            "contract must be lowercase 0x + 40 hex: $contract"
        }
        require(decimals in 0..255) { "decimals out of range: $decimals" }
    }
}
