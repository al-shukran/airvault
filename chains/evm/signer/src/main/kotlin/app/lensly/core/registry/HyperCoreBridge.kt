package app.lensly.core.registry

/*
 * HyperCore is Hyperliquid's trading layer, and HyperEVM is its EVM chain. Assets move between them through system addresses. The HyperCore
 * and HyperEVM transfer page, https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/hyperevm/hypercore-less-than-greater-than-hyperevm-transfers:
 * "The exception is HYPE, which has a system address of `0x2222222222222222222222222222222222222222`". Every other token: "Every token has a
 * system address on the Core, which is the address with first byte `0x20`" and "the remaining bytes all zeros, except for the token index
 * encoded in big-endian format." Its example: "for token index 200, the system address would be `0x20000000000000000000000000000000000000c8`".
 * HyperCore to HyperEVM: "Transferring tokens from HyperCore to HyperEVM can be done using a sendAsset action" with "the corresponding system
 * address as the destination". HyperEVM to HyperCore: "Transferring tokens from HyperEVM to HyperCore can be done using an ERC20 transfer" with
 * "the corresponding system address as the destination", and "To transfer back to HyperCore, HYPE can be sent as a transaction value."
 * The chain IDs, https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/hyperevm: "Chain ID: 999" for mainnet and "Chain ID: 998" for testnet.
 */

/** HYPE's system address on HyperCore, which HyperEVM also sends HYPE to. */
private const val HYPE_SYSTEM_ADDRESS = "0x2222222222222222222222222222222222222222"

/**
 * A token's system address: 0x20, fifteen zero bytes, then the token index as a four-byte big-endian number. Lensly reads an index of up to four
 * bytes, so an address with a longer index is not labelled.
 */
private val TOKEN_SYSTEM_ADDRESS = Regex("0x20" + "0".repeat(30) + "[0-9a-f]{8}")

/** The HyperEVM chain IDs: mainnet and testnet. */
private val HYPEREVM_CHAIN_IDS = setOf(999L, 998L)

/** True when [chainId] is HyperEVM, mainnet or testnet. */
fun isHyperEvm(chainId: Long): Boolean = chainId in HYPEREVM_CHAIN_IDS

/** True when [address] is HYPE's system address, in any letter case. */
fun isHypeSystemAddress(address: String): Boolean = address.lowercase() == HYPE_SYSTEM_ADDRESS

/** True when [address] is a token's system address, in any letter case. */
fun isTokenSystemAddress(address: String): Boolean = TOKEN_SYSTEM_ADDRESS.matches(address.lowercase())

/** True when [address] is HYPE's system address or a token's. */
fun isSystemAddress(address: String): Boolean = isHypeSystemAddress(address) || isTokenSystemAddress(address)
