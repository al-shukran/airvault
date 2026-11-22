package app.lumio.send.solana

import app.lumio.data.provider.SolanaSignatureStatus
import app.lumio.data.provider.SolanaStatus
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.send.Landing
import app.lumio.send.solanaLanding

/**
 * Where one sent Solana transaction stands, read on each poll of its watch. Without a status, the transaction is refused only on the second poll in a
 * row that finds it dropped (see [SolanaSend.dropped]), and that poll re-reads the status right before refusing: a refusal is final, so one lagging
 * read must not refuse a send that landed. The drop is read from the chain before the status, so a transaction that lands in between is never taken
 * for a dropped one.
 */
class SolanaSendLanding(private val scheduler: Scheduler, private val plan: SolanaSendPlan) {
    private var noticed = false

    suspend fun landing(signature: String): Landing {
        val dropped = SolanaSend.dropped(scheduler, plan)
        val status = status(signature)
        if (status != null || !dropped) {
            noticed = false
            return solanaLanding(status, dropped)
        }
        if (!noticed) {
            noticed = true
            return Landing.PENDING
        }
        return solanaLanding(status(signature), dropped = true)
    }

    private suspend fun status(signature: String): SolanaStatus? =
        scheduler.run(SolanaSignatureStatus(plan.network, signature), Priority.BACKGROUND)
}
