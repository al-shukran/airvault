package app.lumio.send.solana

import app.airvault.chains.solana.signer.SolCluster
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.wallet.SolanaWallet
import kotlin.test.assertIs

/**
 * Lensly's review of [plan], built from the same sign request that Lumio sends for it. [keys] are the wallet's account keys in account order, so the
 * request's account finds its key as Lensly finds it at sign time. The review is what the owner reads and what Lensly signs.
 */
internal fun reviewOf(plan: SolanaSendPlan, keys: List<ByteArray>): SolanaRequestReview {
    val request = SolSignRequest(
        requestId = plan.requestId,
        signData = plan.message,
        derivationPath = SolanaWallet.pathOf(plan.account.index),
        sourceFingerprint = plan.account.sourceFingerprint,
        address = plan.account.key,
        origin = "Lumio",
        signType = SolSignType.TRANSACTION,
        cluster = requireNotNull(SolCluster.fromWire(plan.network.name)),
    )
    return assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, keys)).review
}
