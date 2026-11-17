package app.lensly.ui.sign

import app.lensly.app.AddressNotes
import app.lensly.app.VisualIdentity
import app.lensly.app.addressesIn
import app.lensly.app.generatedIdentity
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.evm.AccessListEntry
import app.lensly.core.registry.ContractCall
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry

/** A fact the owner ticks before signing, because the review cannot prove it on its own. */
enum class ReviewCheck {
    /** The request matches the denylist of known-dangerous requests, and the owner has read the warning. */
    DENYLIST,

    /** The chain ID and the contract are outside the registry, and the owner has checked them. */
    REGISTRY,

    /**
     * An unknown call that the signed whitelist does not approve. The owner has read that Lensly cannot say what it does.
     */
    UNKNOWN_CALL,

    /** An approval that lets the spender take every token of the contract, and the owner has read that it is unlimited. */
    UNLIMITED,

    /** The call is on the signed whitelist, and the owner has read its approved meaning. */
    WHITELIST,

    /** The call goes to a whitelisted proxy under Warn and confirm, and the owner accepts that its code cannot be checked. */
    UPGRADE,
}

/**
 * The [TxReview.function] of a personal message. A message has no recipient, amount, nonce or fee, and its text is
 * the only parameter, so the review screen shows the message instead of those facts.
 */
const val SIGN_MESSAGE_FUNCTION = "Sign message"

/** The name of a token that is not in the registry. */
const val UNKNOWN_TOKEN_NAME = "Unknown token"

/** The unit of an amount whose coin or token is not known. The amount is then shown as raw base units. */
const val UNKNOWN_UNIT = "base units"

/** The [TxReview.amountNote] of a token amount whose decimals are not known. */
const val UNKNOWN_DECIMALS_NOTE = "Decimals unknown: raw integer amount, not verified"

/** The warning on an approval that lets the spender take every token. [symbol] is the token, or null when it is unknown. */
fun unlimitedApprovalWarning(symbol: String?): String =
    "${ContractCall.UNLIMITED_APPROVAL_PREFIX} this contract can spend all your ${symbol ?: "tokens of this contract"}"

/** True when [warning] is an unlimited-approval warning, whatever token it names. */
fun isUnlimitedApprovalWarning(warning: String): Boolean = warning.startsWith(ContractCall.UNLIMITED_APPROVAL_PREFIX)

/** The warning on `setApprovalForAll(operator, true)`: the operator controls every token of the contract. */
const val ALL_NFTS_WARNING = "Grants control of ALL your NFTs in this collection"

/**
 * The warning on a call Lensly cannot read that the signed whitelist does not approve. The Warn policy reviews it under this warning, and
 * the owner ticks [ReviewCheck.UNKNOWN_CALL] before signing.
 */
const val UNKNOWN_CALL_WARNING =
    "Unknown contract call. Lensly does not read this call, so it cannot say what it does. " +
        "Check the contract and the calldata before you sign."

/**
 * One call inside a multicall, as the review lists it. [depth] is its level: 1 for a call of the outer multicall, 2 for a call inside
 * a nested one. [target] is the contract a Multicall3 call names, and null for a call to the contract the batch itself goes to.
 * [whitelisted] is set when the signed whitelist approves the call, which Lensly cannot decode itself.
 *
 * A call carries the same warnings a lone call would: [warnings] such as an unlimited approval, the muted [note] under its amounts,
 * and [unregistered] when it moves or approves a token outside the registry. Each is shown as it is for a lone call. [denylisted]
 * holds the denylist entries the call matches, each a WARN, since a BLOCK match refuses the request before its review.
 */
data class NestedCall(
    val depth: Int,
    val target: String?,
    val function: String,
    val params: List<Pair<String, String>>,
    val whitelisted: WhitelistedCall? = null,
    val warnings: List<String> = emptyList(),
    val note: String? = null,
    val unregistered: Boolean = false,
    val denylisted: List<DenylistEntry> = emptyList(),
)

/**
 * Everything the review screen shows about one transaction before the user decides.
 *
 * [amount] is already formatted for display, for example "0.0100". [fee] holds label and value
 * pairs such as "Max fee" to "52.5 gwei". [params] holds decoded call arguments, and is empty for a
 * native transfer. [warnings] are shown first, in red. A chain or token outside the registry is shown
 * with [unknownNetworkName] or [UNKNOWN_TOKEN_NAME] and raw base units, and [needsRegistryConfirmation]
 * is then true.
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
    /**
     * The muted line under the amount. For an unknown chain it is the value under an assumed 18 decimals, marked as
     * an assumption. For an unknown token it says the decimals are unknown. Null for a known coin or token.
     */
    val amountNote: String? = null,
    /** The ABI signature of a contract call, such as transfer(address,uint256). Null for a native transfer and a message. */
    val signature: String? = null,
    /** The wallet that signs, as the review header names it. */
    val walletName: String = "",
    /** The Visual Identity of the wallet that signs. */
    val walletIdentity: VisualIdentity = generatedIdentity(0),
    /** The Visual Identity of the account that signs. */
    val accountIdentity: VisualIdentity = generatedIdentity(0),
    /** The origin the request names, such as the wallet's site, or null when the request names none. */
    val origin: String? = null,
    /** The whitelisted call this review is for, or null for a call Lensly decodes. */
    val whitelisted: WhitelistedCall? = null,
    /** The denylist entries this request matches, each a known-dangerous request. Empty when none matches. */
    val denylisted: List<DenylistEntry> = emptyList(),
    /** The calls of a multicall, level by level, in the order they run. Empty for any other call. */
    val nested: List<NestedCall> = emptyList(),
    /** What a typed data (EIP-712) review shows: the domain, the Permit summary and every field. Null for any other request. */
    val typed: TypedReview? = null,
    /** What the Address Book and the device's accounts say of the addresses shown, keyed by [app.lensly.app.addressKeyOf]. Empty when none. */
    val addressNotes: Map<String, AddressNotes> = emptyMap(),
    /** The access list of the transaction, which the review lists in its details. Empty when there is none. */
    val accessList: List<AccessListEntry> = emptyList(),
) {
    /**
     * True when the chain or the token is outside the registry. Such a review carries the registry warning, and APPROVE
     * waits for the user's check of the chain ID and contract. It follows from [network], [token] and [typed], so they cannot
     * disagree with it.
     */
    val needsRegistryConfirmation: Boolean
        get() = network == null || (tokenContract != null && token == null) || typed?.hasUnknownToken == true

    /** The checks the owner ticks before signing, in the order the screen shows them. */
    val requiredChecks: List<ReviewCheck>
        get() {
            // A whitelisted or unregistered call may sit in a multicall, so every such call of the review counts.
            val whitelistedCalls = listOfNotNull(whitelisted) + nested.mapNotNull { it.whitelisted }
            val unknownCall = UNKNOWN_CALL_WARNING in warnings || nested.any { UNKNOWN_CALL_WARNING in it.warnings }
            val unlimited = (warnings + nested.flatMap { it.warnings }).any(::isUnlimitedApprovalWarning)
            return listOfNotNull(
                ReviewCheck.DENYLIST.takeIf { denylisted.isNotEmpty() || nested.any { it.denylisted.isNotEmpty() } },
                ReviewCheck.REGISTRY.takeIf { needsRegistryConfirmation || nested.any { it.unregistered } },
                ReviewCheck.UNKNOWN_CALL.takeIf { unknownCall },
                ReviewCheck.UNLIMITED.takeIf { unlimited },
                ReviewCheck.WHITELIST.takeIf { whitelistedCalls.isNotEmpty() },
                ReviewCheck.UPGRADE.takeIf { whitelistedCalls.any { it.call.proxy != null } },
            )
        }

    /**
     * True when the review holds a warning, so its risk banner has a row. The rows are the denylist matches, the registry, whitelist and
     * proxy checks, and the plain warnings, of the review and of its calls. Such a review shows its banner first, and its details below it.
     */
    val hasWarnings: Boolean
        get() = warnings.isNotEmpty() || requiredChecks.isNotEmpty() || nested.any { it.warnings.isNotEmpty() }

    /**
     * True when the hold-to-sign control may run: the review has been read to its end, and every required check is ticked. [everyFactShown]
     * is that read, which the review screen decides by its scroll.
     */
    fun canApprove(everyFactShown: Boolean, checked: Set<ReviewCheck>): Boolean =
        everyFactShown && checked.containsAll(requiredChecks)

    /**
     * Every address the review names, in the order the review shows them: the account that signs, the recipient, the token contract,
     * the contract of a whitelisted call and its proxy implementation, the arguments and the calls, and the fields of a typed message.
     * Each is looked up in the Address Book, so a counterparty that is not saved there is shown as not in the Address Book.
     */
    val counterparties: List<String>
        get() = addressesIn(
            buildList {
                add(fromAddress)
                add(toAddress)
                tokenContract?.let(::add)
                whitelisted?.let { w ->
                    add(w.call.target)
                    w.call.proxy?.implementationAddress?.let(::add)
                }
                params.forEach { add(it.second) }
                nested.forEach { call ->
                    call.target?.let(::add)
                    call.params.forEach { add(it.second) }
                    call.whitelisted?.let { w -> add(w.call.target) }
                }
                typed?.let { typed ->
                    typed.domain.forEach { add(it.second) }
                    typed.summary.forEach { add(it.second) }
                    typed.fields.forEach { row -> row.value?.let(::add) }
                }
                accessList.forEach { add(it.address) }
            },
        )
}
