package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import java.net.URI
import java.net.URISyntaxException

/**
 * The Esplora server each Bitcoin network reads from. The defaults are public servers, and the owner can point a network at another
 * Esplora, such as their own node. Only HTTPS is accepted: the addresses that Lumio asks about are the owner's private data.
 * Each network has its own server, so a mainnet override never changes testnet4.
 */
object EsploraServers {
    const val MAINNET_DEFAULT = "https://mempool.space/api"
    const val TESTNET4_DEFAULT = "https://mempool.space/testnet4/api"

    /** The public server of [network]. */
    fun defaultFor(network: BtcNetwork): String = when (network) {
        BtcNetwork.MAINNET -> MAINNET_DEFAULT
        BtcNetwork.TESTNET4 -> TESTNET4_DEFAULT
    }

    /** The base URL that [network] reads from: the owner's override when there is one, otherwise the default. */
    fun baseUrl(network: BtcNetwork, overrides: Map<BtcNetwork, String>): String = overrides[network] ?: defaultFor(network)

    /**
     * The base URL that [input] names, with no trailing slash.
     * @throws IllegalArgumentException with the reason, when [input] is not an https URL that names a host.
     */
    fun normalize(input: String): String {
        val text = input.trim().trimEnd('/')
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("The address is not a valid URL.", e)
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "The address must start with https://." }
        require(!uri.host.isNullOrEmpty()) { "The address must name a server." }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "The address must be a plain base URL, without a query, a fragment or a user name."
        }
        return text
    }
}
