package app.lensly.core.registry

import app.airvault.abi.DecodedParam
import java.math.BigInteger

/** The line a call shows when Lensly recognises it but cannot read its arguments, so the raw arguments stay the only account of it. */
internal const val UNREADABLE_CALL = "Lensly cannot read this call. Its raw arguments are shown under Details."

/**
 * The human-readable steps of an approved call that Lensly describes, and the warnings its steps raise. [lines] are the steps in the
 * order the contract or router runs them, one plain sentence each. [warnings] go to the review's warning list, so a step Lensly cannot
 * read is never shown without a warning. [addresses] are the other accounts the steps pay, which the review shows as address rows.
 * [leavesInRouter] is set when a router step leaves ETH or tokens in the router that no later step takes out, so the review warns of it as a DANGER.
 */
data class CallSteps(
    val lines: List<String>,
    val warnings: List<String>,
    val addresses: List<String> = emptyList(),
    val leavesInRouter: Boolean = false,
) {
    companion object {
        /**
         * The steps of the approved call with [selector] and [signature] to [contract] on [chainId], whose decoded arguments are [params]
         * as the sandbox read them. [from] is the signing address, [caller] the address the contract sees as its message sender (the batch
         * contract inside a batch), and [value] the native amount the call sends. Null when Lensly does not
         * describe that contract and selector, or the network is not in [registry], so the review shows only the approved meaning and the
         * raw arguments.
         */
        fun describe(
            registry: Registry,
            chainId: Long,
            contract: String,
            selector: String,
            signature: String,
            from: String,
            value: BigInteger,
            params: List<DecodedParam>,
            caller: String = from,
        ): CallSteps? {
            val network = registry.network(chainId) ?: return null
            val call = StepCall(registry, network, contract.lowercase(), from.lowercase(), value, params, caller.lowercase())
            // An approved call whose arguments do not have the shape its entry names warns, and never reaches the sign flow as a crash.
            return try {
                UniversalRouterSteps.describe(call, selector.lowercase(), signature)
                    ?: SwapRouterSteps.describe(call, selector.lowercase(), signature)
                    ?: ProtocolSteps.describe(call, selector.lowercase(), signature)
                    ?: BulkerSteps.describe(call, selector.lowercase(), signature)
                    ?: CowSteps.describe(call, selector.lowercase(), signature)
            } catch (e: RuntimeException) {
                CallSteps(emptyList(), listOf(UNREADABLE_CALL))
            }
        }
    }
}
