package app.lumio.ui.settings

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.EsploraServers

/** What the owner's Esplora server entry does: nothing, a change to [url] (null is the public default), or a refusal that names its [reason]. */
sealed interface ServerEdit {
    data object Unchanged : ServerEdit

    data class Apply(val url: String?) : ServerEdit

    data class Refused(val reason: String) : ServerEdit
}

/**
 * What the server [text] does for [network], given the [current] base URL that the network reads from. Blank text means the public server. A text that
 * is not an https address is refused, and the reason names the network. A valid text that names [current] changes nothing, and the public server is
 * applied as no override at all.
 */
fun serverEdit(network: BtcNetwork, text: String, current: String): ServerEdit {
    val trimmed = text.trim()
    val url = if (trimmed.isEmpty()) {
        EsploraServers.defaultFor(network)
    } else {
        try {
            EsploraServers.normalize(trimmed)
        } catch (e: IllegalArgumentException) {
            return ServerEdit.Refused("${networkName(network)}: ${e.message}")
        }
    }
    if (url == current) return ServerEdit.Unchanged
    return ServerEdit.Apply(url.takeIf { it != EsploraServers.defaultFor(network) })
}

/** The overrides with [network] set to [url], or back on its public server when [url] is null. The other network keeps its own. */
fun Map<BtcNetwork, String>.withServer(network: BtcNetwork, url: String?): Map<BtcNetwork, String> =
    if (url == null) this - network else this + (network to url)

/** The name of [network] as the server fields label it. */
fun networkName(network: BtcNetwork): String = if (network == BtcNetwork.MAINNET) "Mainnet" else "Testnet4"
