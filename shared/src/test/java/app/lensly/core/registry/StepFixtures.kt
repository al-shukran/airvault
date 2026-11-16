package app.lensly.core.registry

import java.math.BigInteger

/** Addresses the step tests name. Lowercase, as the registry and the steps compare them. */
internal const val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
internal const val WETH = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"
internal const val USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7"
internal const val LINK = "0x514910771af9ca656af840dff83e8264ecf986ca"
internal const val ETHG = "0x3fc29836e84e471a053d2d9e80494a867d670ead"
internal const val SIGNER = "0x0bf93ea5a1fa4ce3dd22c7ffd314462d3869777f"

/** Ethereum with USDC, WETH, USDT and LINK. ETHG and UNI are not registered, so the steps show them as an address and a raw amount. */
internal val STEP_REGISTRY = Registry(
    networks = listOf(NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", false)),
    tokens = listOf(
        TokenEntry(1, USDC, "USDC", "USD Coin", 6),
        TokenEntry(1, WETH, "WETH", "Wrapped Ether", 18),
        TokenEntry(1, USDT, "USDT", "Tether USD", 6),
        TokenEntry(1, LINK, "LINK", "Chainlink", 18),
    ),
)

/** The bytes of one ABI word. */
internal const val ABI_WORD = 32

/** A 32-byte ABI word holding [value]. */
internal fun abiWord(value: BigInteger): ByteArray {
    val bytes = value.toByteArray().takeLast(ABI_WORD).toByteArray()
    return ByteArray(ABI_WORD - bytes.size) + bytes
}

/** A 32-byte ABI word holding [address]. */
internal fun abiAddressWord(address: String): ByteArray = abiWord(BigInteger(address.removePrefix("0x"), 16))
