package app.lensly.ui.sign

import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry

/**
 * The [TxReview.function] of a personal message. A message has no recipient, amount, nonce or fee, and its text is
 * the only parameter, so the review screen shows the message instead of those facts.
 */
const val SIGN_MESSAGE_FUNCTION = "Sign message"

/**
 * Everything the review screen shows about one transaction before the user decides.
 *
 * [amount] is already formatted for display, for example "0.0100". [fee] holds label and value
 * pairs such as "Max fee" to "52.5 gwei". [params] holds decoded call arguments, and is empty for a
 * native transfer. [warnings] are shown first, in red.
 */
data class TxReview(
    val networkName: String,
    val chainId: Long,
    val fromAccountLabel: String,
    val fromAddress: String,
    val toAddress: String,
    val amount: String,
    val symbol: String,
    val tokenContract: String?,
    val function: String,
    val params: List<Pair<String, String>>,
    val fee: List<Pair<String, String>>,
    val feeNote: String?,
    val warnings: List<String>,
    val nonce: Long,
    val txType: String,
    /** The network the review is for. It supplies the chain logo. Null when the chain is not in the registry. */
    val network: NetworkEntry? = null,
    /** The token that is moved or approved. It supplies the token logo. Null for a native transfer and a message. */
    val token: TokenEntry? = null,
)
