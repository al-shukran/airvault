package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import kotlinx.serialization.Serializable

private const val SELECTOR_BYTES = 4
private val DENYLIST_SELECTOR = Regex("0x[0-9a-f]{8}")
private val DENYLIST_CONTRACT = Regex("0x[0-9a-f]{40}")

/** What a match does: a warning the owner must confirm before signing, or a refusal that shows the reason. */
@Serializable
enum class DenylistSeverity { WARN, BLOCK }

/**
 * Which calls with a matching selector an entry applies to. [ANY] is every call. [UNLIMITED] is a call whose amount is unlimited,
 * such as approve or increaseAllowance with 2^255 or more. [APPROVED] is an operator grant of true, such as setApprovalForAll.
 */
@Serializable
enum class DenylistCondition { ANY, UNLIMITED, APPROVED }

/**
 * One known-dangerous request in the denylist. An entry names a Function Selector, a contract on a chain, or both. [condition]
 * narrows a selector to the calls that carry the risk, so an ordinary limited approval is not flagged. [primaryType] names an
 * EIP-712 type such as Permit. Typed data is not reviewed yet, so no transaction matches such an entry. [reason] is the short line
 * the owner reads on the warning or the refusal.
 */
@Serializable
data class DenylistEntry(
    /** The chain the entry applies to, or null for every chain. A [contract] needs one. */
    val chainId: Long? = null,
    /** Lowercase 0x-prefixed 20-byte address the entry applies to, or null for any contract. */
    val contract: String? = null,
    /** Lowercase 0x-prefixed 4-byte Function Selector, or null for any call. */
    val selector: String? = null,
    /** An EIP-712 primary type, such as Permit. */
    val primaryType: String? = null,
    val condition: DenylistCondition = DenylistCondition.ANY,
    val severity: DenylistSeverity,
    /** The short reason, in plain words. */
    val reason: String,
) {
    init {
        require(chainId == null || chainId > 0) { "chainId must be positive: $chainId" }
        require(contract == null || (chainId != null && DENYLIST_CONTRACT.matches(contract))) {
            "a contract needs a chainId and a 0x address of 40 lowercase hex digits"
        }
        require(selector == null || DENYLIST_SELECTOR.matches(selector)) { "selector must be 0x and 8 lowercase hex digits" }
        require(selector != null || contract != null || primaryType != null) {
            "an entry names a selector, a contract or a primary type"
        }
        require(condition == DenylistCondition.ANY || selector != null) { "only a selector can name an amount or an approval" }
        require(primaryType == null || isDisplayText(primaryType, LABEL_MAX)) {
            "primaryType must be 1 to $LABEL_MAX printable characters"
        }
        require(isDisplayText(reason, NOTE_MAX)) { "reason must be 1 to $NOTE_MAX printable characters" }
    }
}

/**
 * The entries that a request matches: a call with [calldata] to [contract] on [chainId]. An entry whose condition needs the decoded
 * amount still matches calldata that Lensly cannot decode, because the owner should see the warning rather than none.
 */
internal fun matchingDenylist(
    entries: List<DenylistEntry>,
    chainId: Long,
    contract: String,
    calldata: ByteArray,
): List<DenylistEntry> {
    if (entries.isEmpty()) return emptyList()
    val selector = if (calldata.size >= SELECTOR_BYTES) "0x" + calldata.copyOfRange(0, SELECTOR_BYTES).toHex() else null
    val call = ContractCall.decode(calldata)
    return entries.filter { it.matches(chainId, contract, selector, call) }
}

private fun DenylistEntry.matches(chainId: Long, contract: String, selector: String?, call: ContractCall?): Boolean {
    if (this.selector == null && this.contract == null) return false
    if (this.chainId != null && this.chainId != chainId) return false
    if (this.contract != null && !this.contract.equals(contract, ignoreCase = true)) return false
    if (this.selector != null && this.selector != selector) return false
    return when (condition) {
        DenylistCondition.ANY -> true
        DenylistCondition.UNLIMITED -> call == null || when (call) {
            is ContractCall.Approve -> call.unlimited
            is ContractCall.IncreaseAllowance -> call.unlimited
            else -> false
        }
        DenylistCondition.APPROVED -> call == null || (call is ContractCall.SetApprovalForAll && call.approved)
    }
}
