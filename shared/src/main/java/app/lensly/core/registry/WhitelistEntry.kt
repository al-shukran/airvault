package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import kotlinx.serialization.Serializable
import org.web3j.crypto.Hash

private val SELECTOR_PATTERN = Regex("0x[0-9a-f]{8}")
private val HASH_PATTERN = Regex("0x[0-9a-f]{64}")
private val SIGNATURE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*\\((?:[a-z0-9\\[\\]]+(?:,[a-z0-9\\[\\]]+)*)?\\)")
private val INTEGER_TYPES = (8..256 step 8).flatMap { listOf("uint$it", "int$it") }.toSet()
private val BYTES_TYPES = (1..32).map { "bytes$it" }.toSet()

/** True for an ABI type whose value is one 32-byte word: address, bool, uintN, intN or bytesN. */
internal fun isStaticType(type: String): Boolean =
    type == "address" || type == "bool" || type in INTEGER_TYPES || type in BYTES_TYPES

/** The ABI types of [signature], in order, such as [address, uint256] for transfer(address,uint256). */
internal fun signatureTypes(signature: String): List<String> =
    signature.substringAfter('(').substringBefore(')').split(',').filter { it.isNotEmpty() }

/**
 * One static argument of a whitelisted call. [name] is the ABI argument name, [type] is its static ABI type, and
 * [tokenRef] marks a uint amount that is a quantity of the contract's own token when the contract is in the registry.
 */
@Serializable
data class WhitelistParam(
    val name: String,
    val type: String,
    val tokenRef: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "param name must not be blank" }
        require(isStaticType(type)) { "param type must be a static ABI type: $type" }
    }
}

/**
 * One contract call the signed whitelist approves although Lensly cannot decode it. An entry pairs a contract with a Function
 * Selector on one network, and says in [meaning] what the call does. [signature] is the function's ABI signature, and its
 * Keccak-256 hash must start with [selector], so the name shown for a selector is the one the selector really has. A target that
 * is an upgradeable proxy carries its [proxy] binding and sets [upgradeable]. The [params] name and type every argument, so the
 * calldata is decoded and shown in full before signing.
 */
@Serializable
data class WhitelistEntry(
    val chainId: Long,
    /** Lowercase 0x-prefixed 20-byte address of the contract the call goes to. */
    val contract: String,
    /** Lowercase 0x-prefixed 4-byte Function Selector. */
    val selector: String,
    /** The ABI signature, such as transfer(address,uint256). */
    val signature: String,
    /** What the call does, in plain words. */
    val meaning: String,
    /** The implementation a proxy target is bound to, or null when the contract is not a proxy. */
    val proxy: ProxyBinding? = null,
    /** The arguments of the call, in signature order. Their count and types must equal the signature's. */
    val params: List<WhitelistParam>,
    /** True when the target is an upgradeable proxy. It must then carry a [proxy] binding. */
    val upgradeable: Boolean,
) {
    init {
        require(chainId > 0) { "chainId must be positive: $chainId" }
        require(CONTRACT_PATTERN.matches(contract)) { "contract must be 0x followed by 40 lowercase hex digits: $contract" }
        require(SELECTOR_PATTERN.matches(selector)) { "selector must be 0x followed by 8 lowercase hex digits: $selector" }
        require(SIGNATURE_PATTERN.matches(signature)) { "signature must look like name(type,type): $signature" }
        require(selector == selectorOf(signature)) { "selector $selector is not the selector of $signature" }
        require(meaning.isNotBlank()) { "meaning must not be blank" }
        val types = signatureTypes(signature)
        require(params.size == types.size) { "params must name every argument of $signature" }
        params.forEachIndexed { index, param ->
            require(param.type == types[index]) { "param ${param.name} must be ${types[index]} in $signature" }
        }
        if (upgradeable) require(proxy != null) { "an upgradeable entry must name its proxy binding" }
        if (proxy != null) require(upgradeable) { "a proxy binding belongs to an upgradeable entry" }
    }

    companion object {
        /** The Function Selector of [signature]: the first four bytes of its Keccak-256 hash, as 0x and 8 lowercase hex digits. */
        fun selectorOf(signature: String): String =
            "0x" + Hash.sha3(signature.toByteArray(Charsets.US_ASCII)).copyOf(4).toHex()
    }
}

/**
 * The implementation a whitelisted proxy is bound to: its address, its code hash, or both. The binding is what the owner approved.
 * It is not what the proxy runs now, because an offline phone cannot read that.
 */
@Serializable
data class ProxyBinding(
    /** Lowercase 0x-prefixed 20-byte address of the implementation. */
    val implementationAddress: String? = null,
    /** Lowercase 0x-prefixed Keccak-256 hash of the implementation's code. */
    val implementationCodeHash: String? = null,
) {
    init {
        require(implementationAddress != null || implementationCodeHash != null) {
            "a proxy binding names the implementation, its code hash, or both"
        }
        implementationAddress?.let {
            require(CONTRACT_PATTERN.matches(it)) { "implementation address must be 0x followed by 40 lowercase hex digits: $it" }
        }
        implementationCodeHash?.let {
            require(HASH_PATTERN.matches(it)) { "implementation code hash must be 0x followed by 64 lowercase hex digits" }
        }
    }
}
