package app.lumio.evm

import app.lumio.data.sched.Priority
import app.lumio.send.Landing
import app.lumio.send.evmLanding

/**
 * Where one sent EVM transaction stands, read on each poll of its watch. A transaction with no receipt is dropped once the account's confirmed nonce
 * has passed its nonce. That refuses it only on the second poll in a row that sees it, and the second poll re-reads the receipt right before refusing,
 * since a refusal is final and one lagging read must not refuse a send that landed. The nonce is read before the receipt, so a transaction mined
 * between the two reads is never taken for a dropped one.
 */
class EvmSendLanding(private val reads: EvmReads, private val send: PreparedSend) {
    private var noticed = false

    suspend fun landing(hash: String): Landing {
        val nonceUsed = reads.nonce(send.chain, send.from, pending = false, Priority.BACKGROUND) > send.nonce
        val receipt = reads.receipt(send.chain, hash)
        if (receipt != null || !nonceUsed) {
            noticed = false
            return evmLanding(receipt, nonceUsed)
        }
        if (!noticed) {
            noticed = true
            return Landing.PENDING
        }
        return evmLanding(reads.receipt(send.chain, hash), nonceUsed = true)
    }
}
