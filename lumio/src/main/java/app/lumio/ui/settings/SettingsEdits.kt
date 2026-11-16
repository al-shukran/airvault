package app.lumio.ui.settings

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.EsploraServers
import app.lumio.data.config.MinimumUsd

/** What the owner's minimum-balance entry does: nothing, a change to [amount], or a refusal that names its [reason]. */
sealed interface MinimumEdit {
    data object Unchanged : MinimumEdit

    data class Apply(val amount: Double) : MinimumEdit

    data class Refused(val reason: String) : MinimumEdit
}

/** What the owner's Esplora server entry does: nothing, a change to [url] (null is the public default), or a refusal that names its [reason]. */
sealed interface ServerEdit {
    data object Unchanged : ServerEdit

    data class Apply(val url: String?) : ServerEdit

    data class Refused(val reason: String) : ServerEdit
}

/** The refusal of a minimum that is not an amount. */
const val MINIMUM_REFUSAL = "Enter an amount from 0 to 1,000,000, with at most two decimals."

/**
 * What the minimum [text] does, given the [current] minimum that the screen holds. A valid amount that differs from [current] is applied at once. A text
 * that is not an amount is refused, and nothing is applied.
 */
fun minimumEdit(text: String, current: Double): MinimumEdit {
    val amount = MinimumUsd.parse(text) ?: return MinimumEdit.Refused(MINIMUM_REFUSAL)
    return if (amount == current) MinimumEdit.Unchanged else MinimumEdit.Apply(amount)
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
