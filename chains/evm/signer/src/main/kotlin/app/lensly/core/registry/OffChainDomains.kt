package app.lensly.core.registry

import app.airvault.chains.evm.signer.TypedValue
import app.lensly.core.wallet.toHex
import java.math.BigInteger

private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

/**
 * An off-chain signing domain that has a friendly product label. A typed-data domain matches an entry only when its name, version, chain ID and
 * verifying contract all equal the entry's, and the review then titles the signature with [title]. The label adds to the review. It never decides
 * whether a message can be signed: every typed-data domain is signable, whichever chain it names.
 */
data class OffChainDomain(
    val name: String,
    val version: String,
    val chainId: Long,
    /** Lowercase 0x-prefixed 20-byte address. */
    val verifyingContract: String,
    /** The product the signature is for. */
    val label: String,
) {
    /** The title the review gives the signature. */
    val title: String get() = "Off-Chain Signature for $label"
}

/**
 * The off-chain domains that get a product label. Each entry is the exact domain of a published message format, and its source is named beside it.
 */
val OFF_CHAIN_DOMAINS: List<OffChainDomain> = listOf(
    // Hyperliquid L1 action, the phantom agent of source "a" or "b". hyperliquid-python-sdk, hyperliquid/utils/signing.py, l1_payload.
    OffChainDomain("Exchange", "1", 1337, ZERO_ADDRESS, "Hyperliquid"),
    // Hyperliquid user-signed action. signing.py, user_signed_payload; sign_user_signed_action sets signatureChainId to 0x66eee (421614).
    OffChainDomain("HyperliquidSignTransaction", "1", 421614, ZERO_ADDRESS, "Hyperliquid"),
    // AsterDex agent approval, registerAndApproveAgent, for an EVM user. github.com/asterdex/api-docs, V3 futures doc, "Register and Approve Agent".
    OffChainDomain("AsterSignTransaction", "1", 56, ZERO_ADDRESS, "AsterDex"),
    // AsterDex master-account signature, such as sub-account creation. The same doc, "Supported Signing Algorithms" (chainId=1666).
    OffChainDomain("AsterSignTransaction", "1", 1666, ZERO_ADDRESS, "AsterDex"),
)

/** The labelled off-chain domain that [domain] is, or null. Every member the entry names must be present and equal to it. */
fun offChainDomainOf(domain: TypedValue.StructValue): OffChainDomain? {
    val name = (domain.fields["name"] as? TypedValue.TextValue)?.value ?: return null
    val version = (domain.fields["version"] as? TypedValue.TextValue)?.value ?: return null
    val chainId = (domain.fields["chainId"] as? TypedValue.IntValue)?.value ?: return null
    val contract = (domain.fields["verifyingContract"] as? TypedValue.BytesValue)?.let { "0x" + it.bytes.toHex() } ?: return null
    return OFF_CHAIN_DOMAINS.firstOrNull { entry ->
        entry.name == name && entry.version == version && BigInteger.valueOf(entry.chainId) == chainId &&
            entry.verifyingContract == contract
    }
}
