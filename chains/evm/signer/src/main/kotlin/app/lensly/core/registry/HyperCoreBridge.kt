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

/**
 * The HyperEVM mainnet tokens that are linked to a HyperCore token, by lowercase contract address, with the HyperCore token index of each. The
 * system address that moves a token to HyperCore is built from that token's own index, and from no other. Source: the spotMeta response of
 * https://api.hyperliquid.xyz/info, whose tokens list "evmContract" and "index", read on 2026-11-27. A token that is not listed has no known
 * index, so a transfer of it to a system address is never labelled as a move to HyperCore.
 */
private val HYPEREVM_LINKED_TOKENS: Map<String, Int> = mapOf(
    "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24" to 0, // USDC
    "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e" to 1, // PURR
    "0x94e8396e0869c9f2200760af0621afd240e1cf38" to 119, // STHYPE
    "0xfd739d4e423301ce9385c1fb8850539d657c296d" to 121, // KHYPE
    "0x1ecd15865d7f8019d546f76d095d9c93cc34edfa" to 178, // LIQD
    "0x9fdbda0a5e284c32744d2f17ee5c74b284993463" to 197, // UBTC
    "0xbe6727b535545c67d5caa73dea54865b92cf7907" to 221, // UETH
    "0x47bb061c0204af921f43dc73c7d7768d2672ddee" to 222, // BUDDY
    "0x5d3a1ff2b6bab83b63cd9ad0787074081a52ef34" to 235, // USDE
    "0x02c6a2fa58cc01a18b8d9e00ea48d65e4df26c70" to 241, // FEUSD
    "0x068f321fa8fb9f0d135f290ef6a3e2813e1c8a29" to 254, // USOL
    "0xb8ce59fc3717ada4c02eadf9682a9e934f625ebb" to 268, // USDT0
    "0xb50a96253abdf803d85efcdce07ad8becbc52bd5" to 291, // USDHL
    "0xf4d9235269a96aadafc9adae454a0618ebe37949" to 297, // XAUT0
    "0x27ec642013bcb3d80ca3706599d3cda04f6f4452" to 299, // UPUMP
    "0x111111a1a0667d36bd57c0a9f569b98057111111" to 360, // USDH
)

/** HyperEVM mainnet, the chain that [HYPEREVM_LINKED_TOKENS] describes. */
private const val HYPEREVM_MAINNET = 999L

/**
 * The system address that moves [contract] to HyperCore, 0x20 then zeros then the token's own index, or null when [chainId] is not HyperEVM
 * mainnet or the contract has no known index.
 */
fun hyperCoreSystemAddressOf(chainId: Long, contract: String): String? {
    if (chainId != HYPEREVM_MAINNET) return null
    val index = HYPEREVM_LINKED_TOKENS[contract.lowercase()] ?: return null
    return "0x20" + "0".repeat(30) + "%08x".format(index)
}
