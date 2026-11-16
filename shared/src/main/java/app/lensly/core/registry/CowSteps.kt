package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger

/**
 * Steps for `setPreSignature(bytes,bool)` on the CoW Protocol settlement contract on Ethereum mainnet. A pre-signature lets the settlement
 * accept an order without a signature, so a solver may fill the order on its terms until it expires. The order UID is the order digest (32
 * bytes), then the owner (20 bytes), then the expiry (4 bytes), as cowprotocol/contracts has it at ea0c3659 (GPv2Order and GPv2Signing). The
 * settlement accepts a pre-signature only from the order's owner, so a UID owned by another account is shown as a warning, not as a step.
 */
internal object CowSteps {
    private const val SETTLEMENT = "0x9008d19f58aabd9ed0d60971565aa8510560ab41"
    private const val MAINNET = 1L
    private const val SELECTOR = "0xec6cb13f"
    private const val SIGNATURE = "setPreSignature(bytes,bool)"
    private const val DIGEST_BYTES = 32
    private const val OWNER_BYTES = 20
    private const val UID_BYTES = 56
    private const val SHORT_HEAD = 4
    private const val SHORT_TAIL = 4

    /** The steps of [call] when it is an approved CoW pre-signature on mainnet, and null for any other call. */
    fun describe(call: StepCall, selector: String, signature: String): CallSteps? {
        if (call.chainId != MAINNET || call.contract != SETTLEMENT || selector != SELECTOR || signature != SIGNATURE) return null
        val uid = call.bytes(0)
        if (uid.size != UID_BYTES) return CallSteps(emptyList(), listOf(UNREADABLE_CALL))
        val owner = "0x" + uid.copyOfRange(DIGEST_BYTES, DIGEST_BYTES + OWNER_BYTES).toHex()
        if (owner != call.from) {
            return CallSteps(emptyList(), listOf("This pre-signs an order owned by ${shortAddress(owner)}, not you, so the call reverts"))
        }
        val digest = "0x" + uid.copyOfRange(0, DIGEST_BYTES).toHex()
        val order = digest.take(SHORT_HEAD + 2) + "…" + digest.takeLast(SHORT_TAIL)
        // A revocation is not read: no revocation was in the transactions Lensly has vectors for.
        if (!call.bool(1)) return CallSteps(emptyList(), listOf(UNREADABLE_CALL))
        val validTo = BigInteger(1, uid.copyOfRange(DIGEST_BYTES + OWNER_BYTES, UID_BYTES))
        return CallSteps(
            listOf("Pre-sign CoW order $order for you, valid until ${untilText(validTo)}"),
            listOf("Solvers may fill this order on its terms until it expires. Revoke it to cancel."),
        )
    }
}
