package app.lumio.data.price

import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks

/**
 * The price coin of each network's native asset, named by its CoinGecko id. A network with no entry is unpriced, and so is every testnet: a testnet
 * coin never takes a mainnet price.
 *
 * Each EVM entry was checked on 2026-11-16: the price source answered `coingecko:{id}` with a symbol that matches the native symbol of that chain
 * in the registry's mainnet list (shared/src/main/assets/registry/networks.json). A chain is added here only after the same check. Unpriced on that
 * date: 250 (Fantom, the id is not in the source), 252 (its native coin is FRAX, which the source prices under another symbol), 388 (zkCRO),
 * and 3338 (peaq, not in the source). Solana and Algorand mainnet were checked on 2026-11-26: `solana` and `algorand` answer from CoinGecko and from
 * the price source's `coingecko:{id}` form, with the symbols SOL and ALGO.
 */
object NativeCoins {
    /** The CoinGecko id of the native coin of [net], or null when [net] has no mainnet price. */
    fun coinOf(net: NetworkId): String? = when {
        net.testnet -> null
        net.isBtc -> if (net.name == "mainnet") "bitcoin" else null
        net.isEvm -> EVM[net.name]
        net.chain == Networks.SOL_MAINNET -> "solana"
        net.chain == Networks.ALGO_MAINNET -> "algorand"
        else -> null
    }

    private val EVM: Map<String, String> = buildMap {
        // Ethereum, and every chain whose native coin is ETH.
        listOf(
            "1", "10", "130", "169", "324", "480", "1101", "1868", "2741", "4326", "8453", "42161", "42170",
            "48900", "57073", "59144", "60808", "167000", "534352", "747474", "7777777", "1313161554",
        ).forEach { put(it, "ethereum") }
        put("25", "crypto-com-chain")
        put("30", "rootstock")
        put("40", "telos")
        put("56", "binancecoin")
        put("61", "ethereum-classic")
        put("100", "xdai")
        put("122", "fuse-network-token")
        put("137", "polygon-ecosystem-token")
        put("143", "monad")
        put("146", "sonic-3")
        put("196", "okb")
        put("232", "gho")
        put("369", "pulsechain")
        put("999", "hyperliquid")
        put("1088", "metis-token")
        put("1284", "moonbeam")
        put("1285", "moonriver")
        put("1329", "sei-network")
        put("2020", "ronin")
        put("2222", "kava")
        put("5000", "mantle")
        put("8217", "kaia")
        put("9745", "plasma")
        put("13371", "immutable-x")
        put("33139", "apecoin")
        put("42220", "celo")
        put("43114", "avalanche-2")
        put("80094", "berachain-bera")
        put("1666600000", "harmony")
    }
}
