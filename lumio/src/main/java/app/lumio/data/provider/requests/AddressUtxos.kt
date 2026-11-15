package app.lumio.data.provider

import app.lumio.data.net.NetworkId
import kotlinx.serialization.Serializable

/**
 * One unspent Bitcoin output. [valueSats] is in satoshis. [blockHeight] is null while the output is unconfirmed.
 * The package is `app.lumio.data.provider` because [Request] is sealed, and its subclasses must share its package.
 */
@Serializable
data class Utxo(val txid: String, val vout: Int, val valueSats: Long, val blockHeight: Long?)

/**
 * The unspent outputs of a Bitcoin [address], confirmed and unconfirmed. A provider answers with the whole set or with an error, never with
 * part of it, because a balance summed from part of the set is wrong.
 */
class AddressUtxos(net: NetworkId, val address: String) : Request<List<Utxo>>(net, Op.ADDRESS_UTXOS)
