package app.airvault.core.model

private val UR_TYPE_FORMAT = Regex("[a-z0-9-]+")

/**
 * The apps one phone app is built with, indexed for lookup (spec 2.4). Lensly uses `ChainRegistry<SignerApp>` and
 * Lumio uses `ChainRegistry<WalletApp>`.
 *
 * Construction checks every manifest against spec 2.2 and 6.1, and the uniqueness rules of spec 9.1. A broken manifest
 * throws [IllegalArgumentException] that names the rule it breaks. Lookups return null for an unknown id, because their
 * inputs come from QR payloads and addresses, which are untrusted.
 */
class ChainRegistry<A : ChainApp>(private val apps: List<A>) {
    private val chainIndex: Map<ChainId, A>
    private val requestIndex: Map<String, A>

    init {
        val manifests = apps.map { it.manifest }
        manifests.forEach { checkManifest(it) }
        requireUnique("app id", manifests.map { it.id })
        requireUnique("CAIP-2 chain id", manifests.flatMap { m -> m.networks.map { it.chainId } })
        requireUnique("UR type", manifests.flatMap { m -> (m.requestTypes + m.responseTypes).map { it.type } })
        checkSharedKeys(manifests)
        chainIndex = apps.flatMap { app -> app.manifest.networks.map { it.chainId to app } }.toMap()
        requestIndex = apps.flatMap { app -> app.manifest.requestTypes.map { it.type to app } }.toMap()
    }

    /** The app that serves [id], or null when no installed app does. */
    fun byChain(id: ChainId): A? = chainIndex[id]

    /** Every app that declares [namespace], such as `eip155`, in install order. */
    fun byNamespace(namespace: String): List<A> = apps.filter { namespace in it.manifest.namespaces }

    /** The app that parses QR requests of [urType], or null when no installed app parses that type. */
    fun byRequestType(urType: String): A? = requestIndex[urType]

    /** Every network of every installed app, in install order. */
    fun networks(): List<NetworkSpec> = apps.flatMap { it.manifest.networks }

    private fun checkManifest(m: ChainManifest) {
        require(m.apiVersion == AIRVAULT_API_VERSION) {
            "${m.id}: apiVersion ${m.apiVersion} is not supported; the core supports $AIRVAULT_API_VERSION"
        }
        require(m.networks.count { it.primary && it.testnet } <= 1) { "${m.id}: more than one primary testnet" }
        require(m.networks.count { it.primary && !it.testnet } <= 1) { "${m.id}: more than one primary mainnet" }
        if (m.requiresPrimary) {
            require(m.networks.any { it.primary && it.testnet }) { "${m.id}: no primary testnet" }
            require(m.networks.any { it.primary && !it.testnet }) { "${m.id}: no primary mainnet" }
        }
        for (network in m.networks) {
            require(network.chainId.namespace in m.namespaces) {
                "${m.id}: network ${network.chainId} uses a namespace the app does not declare"
            }
            val asset = network.nativeAsset
            require(asset.chain == network.chainId) {
                "${m.id}: network ${network.chainId} has its native asset on ${asset.chain}"
            }
            if (asset is AssetSpec.Native) {
                require(asset.decimals in 0..36) { "${m.id}: native asset ${asset.symbol} has ${asset.decimals} decimals" }
            }
        }
        for (type in m.requestTypes + m.responseTypes) {
            require(UR_TYPE_FORMAT.matches(type.type)) { "${m.id}: UR type ${type.type} is malformed (spec 7.2)" }
        }
        for (scope in m.keys) checkKeyScope(m, scope)
        checkFields(m)
    }

    private fun checkKeyScope(m: ChainManifest, scope: KeyScope) {
        val coinTypes = m.networks.map { it.slip44 }.toSet()
        for (prefix in scope.pathPrefixes) {
            val parts = prefix.parts
            val where = "${m.id}: key path ${prefix.text}"
            require(parts.any { it.hardened }) { "$where has no hardened level (spec 2.2 rule 1)" }
            val coin = parts.getOrNull(1)
            require(coin is PathPart.Fixed && coin.hardened && coin.index in coinTypes) {
                "$where: the coin-type level must be hardened and one of $coinTypes (spec 2.2 rule 2)"
            }
            require(parts.withIndex().all { (level, part) -> part !is PathPart.Wildcard || level >= 2 }) {
                "$where: a wildcard is allowed only at the account level and below (spec 2.2 rule 3)"
            }
            require(scope.curve != Curve.ED25519_SLIP10 || parts.all { it.hardened }) {
                "$where: ED25519_SLIP10 paths must be hardened at every level (spec 2.2 rule 4)"
            }
        }
    }

    /** Spec 2.2 rule 5: two apps may not derive overlapping paths on one curve unless one lists the other. */
    private fun checkSharedKeys(manifests: List<ChainManifest>) {
        for ((i, a) in manifests.withIndex()) {
            for (b in manifests.drop(i + 1)) {
                if (b.id in a.sharesKeysWith || a.id in b.sharesKeysWith) continue
                for (scopeA in a.keys) for (scopeB in b.keys) {
                    if (scopeA.curve != scopeB.curve) continue
                    for (pa in scopeA.pathPrefixes) for (pb in scopeB.pathPrefixes) {
                        require(!overlaps(pa, pb)) {
                            "${a.id} and ${b.id} overlap on ${scopeA.curve}: ${pa.text}, ${pb.text} (spec 2.2 rule 5)"
                        }
                    }
                }
            }
        }
    }

    private fun checkFields(m: ChainManifest) {
        requireUnique("field key in ${m.id}", m.fields.map { it.key })
        for (field in m.fields) {
            require(field.label.isNotBlank()) { "${m.id}: field ${field.key} has no label" }
            val kind = field.kind
            if (kind is FieldKind.MemoBytes) {
                val max = kind.maxBytes
                val fixed = kind.fixedLength
                require(max == null || max >= 0) { "${m.id}: field ${field.key} has a negative maxBytes" }
                require(fixed == null || fixed >= 0) { "${m.id}: field ${field.key} has a negative fixedLength" }
                require(fixed == null || max == null || fixed <= max) {
                    "${m.id}: field ${field.key} has fixedLength $fixed above maxBytes $max"
                }
            }
        }
    }

    private fun <T> requireUnique(what: String, values: List<T>) {
        val duplicate = values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicate == null) { "duplicate $what: $duplicate" }
    }
}

/** Two prefixes overlap when some path starts with both: each level pair agrees on hardening, and fixed indices agree. */
private fun overlaps(a: PathTemplate, b: PathTemplate): Boolean =
    a.parts.zip(b.parts).all { (x, y) ->
        x.hardened == y.hardened && (x !is PathPart.Fixed || y !is PathPart.Fixed || x.index == y.index)
    }
