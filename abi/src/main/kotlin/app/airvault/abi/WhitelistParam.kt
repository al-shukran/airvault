package app.airvault.abi

import kotlinx.serialization.Serializable

/**
 * One argument of a whitelisted call. [name] is the ABI argument name, and [type] is its canonical ABI type, such as uint256, address[],
 * bytes, string or (address,uint24). [tokenRef] marks a uint amount that is a quantity of the contract's own token when the contract is in
 * the registry. [components] names the fields of the tuple at the base of [type], under any arrays, in field order. Each field's [type]
 * must be the type of that field, and [components] is null when the entry names none, so the fields are shown by position.
 */
@Serializable
data class WhitelistParam(
    val name: String,
    val type: String,
    val tokenRef: Boolean = false,
    val components: List<WhitelistParam>? = null,
) {
    init {
        require(name.isNotBlank()) { "param name must not be blank" }
        val parsed = requireNotNull(AbiTypes.parse(type)) { "param type must be a canonical ABI type: $type" }
        if (tokenRef) require(type.startsWith("uint") && parsed is AtomicType) { "only a uint param can be a token amount: $name" }
        components?.let { fields ->
            var base: AbiType = parsed
            while (base is ArrayType) base = base.element
            val tuple = requireNotNull(base as? TupleType) { "param $name names components, but $type is not a tuple" }
            require(fields.size == tuple.components.size) {
                "param $name names ${fields.size} components for ${tuple.components.size} fields"
            }
            fields.forEachIndexed { index, field ->
                require(field.type == tuple.components[index].canonical) {
                    "component ${field.name} must be ${tuple.components[index].canonical}"
                }
            }
            requireNoTokenRefs(fields)
        }
    }

    private companion object {
        /** A token amount is a top-level argument, so no field of a tuple may carry the mark. */
        fun requireNoTokenRefs(fields: List<WhitelistParam>) {
            for (field in fields) {
                require(!field.tokenRef) { "component ${field.name} cannot be a token amount" }
                field.components?.let(::requireNoTokenRefs)
            }
        }
    }
}
