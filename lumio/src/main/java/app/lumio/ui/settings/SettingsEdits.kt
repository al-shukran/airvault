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

/**
 * What the Ordinals indexer [text] does, given the [requested] address last asked for ("" while it is off). Blank text turns the indexer off, which is
 * applied as null. An address that is not https is refused, as an Esplora server's is, and a valid address that was already asked for changes nothing.
 */
fun indexerEdit(text: String, requested: String): ServerEdit {
    val trimmed = text.trim()
    val url = if (trimmed.isEmpty()) {
        null
    } else {
        try {
            EsploraServers.normalize(trimmed)
        } catch (e: IllegalArgumentException) {
            return ServerEdit.Refused("Ordinals Indexer: ${e.message}")
        }
    }
    if (url.orEmpty() == requested) return ServerEdit.Unchanged
    return ServerEdit.Apply(url)
}

/** The overrides with [network] set to [url], or back on its public server when [url] is null. The other network keeps its own. */
fun Map<BtcNetwork, String>.withServer(network: BtcNetwork, url: String?): Map<BtcNetwork, String> =
    if (url == null) this - network else this + (network to url)

/** The name of [network] as the server fields label it. */
fun networkName(network: BtcNetwork): String = if (network == BtcNetwork.MAINNET) "Mainnet" else "Testnet4"
