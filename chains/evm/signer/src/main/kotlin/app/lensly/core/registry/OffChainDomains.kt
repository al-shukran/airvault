package app.lensly.core.registry

import app.airvault.chains.evm.signer.TypedValue
import app.lensly.core.wallet.toHex
import java.math.BigInteger

private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

/** The four members the SDKs of these domains declare. A domain that declares another member, such as a salt, is not labelled. */
private val SDK_DOMAIN_MEMBERS = setOf("name", "version", "chainId", "verifyingContract")

/**
 * The primary types that Hyperliquid's user-signed actions sign on the domain of chain 421614. hyperliquid-python-sdk, hyperliquid/utils/signing.py:
 * each action helper calls sign_user_signed_action, and that sets signatureChainId to 0x66eee.
 */
private val HYPERLIQUID_USER_SIGNED = setOf(
    "HyperliquidTransaction:ApproveAgent",
    "HyperliquidTransaction:ApproveBuilderFee",
    "HyperliquidTransaction:ConvertToMultiSigUser",
    "HyperliquidTransaction:SendAsset",
    "HyperliquidTransaction:SendMultiSig",
    "HyperliquidTransaction:SpotSend",
    "HyperliquidTransaction:TokenDelegate",
    "HyperliquidTransaction:UsdClassTransfer",
    "HyperliquidTransaction:UsdSend",
    "HyperliquidTransaction:UserDexAbstraction",
    "HyperliquidTransaction:UserSetAbstraction",
    "HyperliquidTransaction:Withdraw",
)

/**
 * An off-chain signing domain that has a friendly product label. A typed-data message gets the label when its domain declares exactly the four
 * SDK members, equals this domain's name, version, chain ID and verifying contract, and its primary type is one of [primaryTypes]. The review then
 * titles the signature with [title]. The label adds to the review. It never decides whether a message can be signed.
 */
data class OffChainDomain(
    val name: String,
    val version: String,
    val chainId: Long,
    /** Lowercase 0x-prefixed 20-byte address. */
    val verifyingContract: String,
    /** The product the signature is for. */
    val label: String,
    /** The primary types the product signs on this domain. */
    val primaryTypes: Set<String>,
    /** The primary types whose message carries a hash of the action and not the action, so the review cannot show what the action does. */
    val hashedActionTypes: Set<String> = emptySet(),
    /** The top-level `string` members that hold an address, such as a destination. The review shows one that is a valid 0x address as an address. */
    val stringAddressMembers: Set<String> = emptySet(),
    /** The primary type that lets another key act for the account, or null. The review gives its message the DANGER of an ApproveAgent. */
    val agentApprovalType: String? = null,
    /**
     * The msg parameter that names the agent of an approval, or null when the primary type alone is the approval. AsterDex sends its
     * parameters as one msg text joined by ampersands, so a message of [agentApprovalType] without this parameter is not an approval.
     */
    val agentApprovalParam: String? = null,
) {
    /** The title the review gives the signature. */
    val title: String get() = "Off-Chain Signature for $label"

    /** True when a message with [primaryType] carries a hash of the action and not the action. */
    fun hashesAction(primaryType: String): Boolean = primaryType in hashedActionTypes

    /**
     * The Hyperliquid network that [message] is for, from its `source` ("a" or "b", the phantom agent of an L1 action) or its `hyperliquidChain`
     * ("Mainnet" or "Testnet", a user-signed action), or null for another product or a value the SDK does not write.
     */
    fun hyperliquidNetwork(message: TypedValue.StructValue): HyperliquidNetwork? {
        if (label != "Hyperliquid") return null
        val source = (message.fields["source"] as? TypedValue.TextValue)?.value
        val chain = (message.fields["hyperliquidChain"] as? TypedValue.TextValue)?.value
        return when {
            source == "a" || chain == "Mainnet" -> HyperliquidNetwork.MAINNET
            source == "b" || chain == "Testnet" -> HyperliquidNetwork.TESTNET
            else -> null
        }
    }

    /** True when a message with [primaryType] and [message] lets another key act for the account. */
    fun isAgentApproval(primaryType: String, message: TypedValue.StructValue): Boolean {
        if (primaryType != agentApprovalType) return false
        val param = agentApprovalParam ?: return true
        // A msg that is absent or not text cannot be read for the parameter, so it counts as the approval: the check fails closed.
        val text = (message.fields["msg"] as? TypedValue.TextValue)?.value ?: return true
        return text.split("&").any { it.substringBefore('=') == param }
    }
}

/** The two environments of Hyperliquid. Only the mainnet holds real funds. */
enum class HyperliquidNetwork(val label: String) { MAINNET("Hyperliquid Mainnet"), TESTNET("Hyperliquid Testnet") }

/**
 * The off-chain domains that get a product label. Each entry is the exact domain of a published message format, and its source is named beside it.
 */
val OFF_CHAIN_DOMAINS: List<OffChainDomain> = listOf(
    // Hyperliquid L1 action, the phantom agent of source "a" or "b", primary type Agent. hyperliquid-python-sdk,
    // hyperliquid/utils/signing.py, l1_payload.
    OffChainDomain("Exchange", "1", 1337, ZERO_ADDRESS, "Hyperliquid", setOf("Agent"), hashedActionTypes = setOf("Agent")),
    // Hyperliquid user-signed actions. signing.py, user_signed_payload; sign_user_signed_action sets signatureChainId to 0x66eee (421614).
    OffChainDomain(
        "HyperliquidSignTransaction", "1", 421614, ZERO_ADDRESS, "Hyperliquid", HYPERLIQUID_USER_SIGNED,
        hashedActionTypes = setOf("HyperliquidTransaction:SendMultiSig"), stringAddressMembers = setOf("destination"),
        agentApprovalType = "HyperliquidTransaction:ApproveAgent",
    ),
    // AsterDex agent approval, registerAndApproveAgent, for an EVM user. github.com/asterdex/api-docs, V3 futures doc,
    // "Register and Approve Agent"; its primaryType is Message, and its msg names the agent with agentAddress.
    OffChainDomain(
        "AsterSignTransaction", "1", 56, ZERO_ADDRESS, "AsterDex", setOf("Message"),
        agentApprovalType = "Message", agentApprovalParam = "agentAddress",
    ),
    // AsterDex master-account signature, such as sub-account creation. The same doc, "Supported Signing Algorithms" (chainId=1666).
    OffChainDomain("AsterSignTransaction", "1", 1666, ZERO_ADDRESS, "AsterDex", setOf("Message")),
)

/**
 * The labelled off-chain domain that [domain] is, or null. The domain must declare exactly the four SDK members, and each must equal the entry's.
 * A domain that declares another member is not labelled.
 */
fun offChainDomainOf(domain: TypedValue.StructValue): OffChainDomain? {
    if (domain.fields.keys != SDK_DOMAIN_MEMBERS) return null
    val name = (domain.fields["name"] as? TypedValue.TextValue)?.value ?: return null
    val version = (domain.fields["version"] as? TypedValue.TextValue)?.value ?: return null
    val chainId = (domain.fields["chainId"] as? TypedValue.IntValue)?.value ?: return null
    val contract = (domain.fields["verifyingContract"] as? TypedValue.BytesValue)?.let { "0x" + it.bytes.toHex() } ?: return null
    return OFF_CHAIN_DOMAINS.firstOrNull { entry ->
        entry.name == name && entry.version == version && BigInteger.valueOf(entry.chainId) == chainId &&
            entry.verifyingContract == contract
    }
}

/** The label of a message with [primaryType] on [domain]: the domain must be labelled, and its product must sign that primary type on it. */
fun offChainLabelOf(domain: TypedValue.StructValue, primaryType: String): OffChainDomain? =
    offChainDomainOf(domain)?.takeIf { primaryType in it.primaryTypes }
