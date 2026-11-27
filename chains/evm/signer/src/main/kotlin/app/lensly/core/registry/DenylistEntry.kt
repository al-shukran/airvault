package app.lensly.core.registry

import app.airvault.chains.evm.signer.ContractCall
import app.lensly.core.wallet.toHex
import kotlinx.serialization.Serializable

private const val SELECTOR_BYTES = 4
private const val WORD_BYTES = 32
private const val ADDRESS_BYTES = 20
private val DENYLIST_SELECTOR = Regex("0x[0-9a-f]{8}")
private val DENYLIST_CONTRACT = Regex("0x[0-9a-f]{40}")

/** What a match does: a warning the owner must confirm before signing, or a refusal that shows the reason. */
@Serializable
enum class DenylistSeverity { WARN, BLOCK }

/**
 * Which calls with a matching selector an entry applies to. [ANY] is every call. [UNLIMITED] is a call whose amount is unlimited,
 * such as approve or increaseAllowance with 2^160 - 1 or more. [APPROVED] is an operator grant of true, such as setApprovalForAll.
 */
@Serializable
enum class DenylistCondition { ANY, UNLIMITED, APPROVED }

/**
 * One known-dangerous request in the denylist. An entry names a Function Selector, a contract on a chain, a [party], or a mix.
 * [condition] narrows a selector to the calls that carry the risk, so an ordinary limited approval is not flagged. [primaryType]
 * names an EIP-712 type such as Permit. It matches typed data of that type, and no transaction, so it names no selector. [party]
 * names an address that appears inside a request: any address word of the calldata, any address value of typed data, or the
 * address a call goes to. [reason] is the short line the owner reads on the warning or the refusal.
 */
@Serializable
data class DenylistEntry(
    /** The chain the entry applies to, or null for every chain. A [contract] needs one. */
    val chainId: Long? = null,
    /** Lowercase 0x-prefixed 20-byte address the entry applies to, or null for any contract. */
    val contract: String? = null,
    /** Lowercase 0x-prefixed 20-byte address that must appear in the request as a counterparty, or null. It needs no chain. */
    val party: String? = null,
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
        require(party == null || DENYLIST_CONTRACT.matches(party)) { "a party must be a 0x address of 40 lowercase hex digits" }
        require(selector == null || DENYLIST_SELECTOR.matches(selector)) { "selector must be 0x and 8 lowercase hex digits" }
        require(selector != null || contract != null || primaryType != null || party != null) {
            "an entry names a selector, a contract, a party or a primary type"
        }
        require(condition == DenylistCondition.ANY || selector != null) { "only a selector can name an amount or an approval" }
        require(primaryType == null || isDisplayText(primaryType, LABEL_MAX)) {
            "primaryType must be 1 to $LABEL_MAX printable characters"
        }
        require(primaryType == null || selector == null) { "an entry names a selector or a primaryType, not both" }
        require(isDisplayText(reason, NOTE_MAX)) { "reason must be 1 to $NOTE_MAX printable characters" }
    }
}

/**
 * The entries that a request matches: a call with [calldata] to [contract] on [chainId]. An entry whose condition needs the decoded
 * amount still matches calldata that Lensly cannot decode, because the owner should see the warning rather than none.
 */
fun matchingDenylist(
    entries: List<DenylistEntry>,
    chainId: Long,
    contract: String,
    calldata: ByteArray,
): List<DenylistEntry> {
    if (entries.isEmpty()) return emptyList()
    val selector = if (calldata.size >= SELECTOR_BYTES) "0x" + calldata.copyOfRange(0, SELECTOR_BYTES).toHex() else null
    val call = ContractCall.decode(calldata)
    // A party is matched against the low 20 bytes of every whole word after the selector, so calldata that does not decode
    // exactly (a trailing byte, dirty upper bytes, a call with no decoder) still names its addresses, as the contract reads them.
    val parties = if (entries.any { it.party != null }) listOf(contract) + wordAddresses(calldata) else emptyList()
    return entries.filter { it.matches(chainId, contract, selector, call, parties) }
}

/** The low 20 bytes of every whole 32-byte word after the selector, as lowercase 0x hex. A trailing partial word is not an address. */
private fun wordAddresses(calldata: ByteArray): List<String> =
    (SELECTOR_BYTES..calldata.size - WORD_BYTES step WORD_BYTES).map { start ->
        "0x" + calldata.copyOfRange(start + WORD_BYTES - ADDRESS_BYTES, start + WORD_BYTES).toHex()
    }

/**
 * The entries that typed data of [primaryType] matches: a message whose domain names [chainId] and, when the domain names one,
 * [verifyingContract], and whose message holds [parties] as address values. An entry matches only when it names that
 * primary type or a party, and its chain, contract and party when it names them. A null [chainId] is a message that names no chain,
 * so it matches the entries of every chain.
 */
fun matchingTypedDenylist(
    entries: List<DenylistEntry>,
    chainId: Long?,
    verifyingContract: String?,
    primaryType: String,
    parties: List<String> = emptyList(),
): List<DenylistEntry> = entries.filter { entry ->
    entry.selector == null && (entry.primaryType != null || entry.party != null) &&
        (entry.primaryType == null || entry.primaryType == primaryType) &&
        (chainId == null || entry.chainId == null || entry.chainId == chainId) &&
        (entry.contract == null || (verifyingContract != null && entry.contract.equals(verifyingContract, ignoreCase = true))) &&
        (entry.party == null || parties.any { it.equals(entry.party, ignoreCase = true) })
}

private fun DenylistEntry.matches(
    chainId: Long,
    contract: String,
    selector: String?,
    call: ContractCall?,
    parties: List<String>,
): Boolean {
    // A primary-type entry names typed data, so no transaction matches it, even one sent to its contract.
    if (primaryType != null || (this.selector == null && this.contract == null && party == null)) return false
    if (this.chainId != null && this.chainId != chainId) return false
    if (this.contract != null && !this.contract.equals(contract, ignoreCase = true)) return false
    if (party != null && parties.none { it.equals(party, ignoreCase = true) }) return false
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
