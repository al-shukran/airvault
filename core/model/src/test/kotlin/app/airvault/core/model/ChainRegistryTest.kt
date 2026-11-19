package app.airvault.core.model

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChainRegistryTest {
    @Test
    fun indexesAValidManifest() {
        val app = FakeApp(baseline())
        val registry = ChainRegistry(listOf(app))

        assertSame(app, registry.byChain(ChainId("testns:1")))
        assertNull(registry.byChain(ChainId("testns:2")))
        assertEquals(listOf(app), registry.byNamespace("testns"))
        assertTrue(registry.byNamespace("other").isEmpty())
        assertSame(app, registry.byRequestType("test-sign-request"))
        assertNull(registry.byRequestType("test-signature"))
        assertEquals(listOf("testns:1"), registry.networks().map { it.chainId.caip2 })
    }

    @Test
    fun rejectsDuplicateAppIds() {
        assertRejectedPair("duplicate app id", baseline(), baseline(chain = "other:1", namespace = "other"))
    }

    @Test
    fun rejectsDuplicateChainIds() {
        assertRejectedPair("duplicate CAIP-2 chain id", baseline(id = "first"), baseline(id = "second"))
    }

    @Test
    fun rejectsDuplicateAndMalformedUrTypes() {
        assertRejectedPair(
            "duplicate UR type",
            baseline(id = "first"),
            baseline(id = "second", chain = "other:1", namespace = "other"),
        )
        assertRejected(
            "duplicate UR type",
            baseline().copy(responseTypes = listOf(UrTypeSpec("test-sign-request", Direction.RESPONSE, 1..1))),
        )
        assertRejected(
            "malformed",
            baseline().copy(requestTypes = listOf(UrTypeSpec("test sign", Direction.REQUEST, 1..1))),
        )
    }

    @Test
    fun rejectsUppercaseUrTypes() {
        assertRejected(
            "malformed",
            baseline().copy(requestTypes = listOf(UrTypeSpec("Test-sign-request", Direction.REQUEST, 1..1))),
        )
    }

    @Test
    fun rejectsAnAppThatRequiresAPrimaryNetworkOnEitherSide() {
        val testnet = network("testns:1").copy(primary = true)
        val mainnet = network("testns:2").copy(testnet = false, primary = true)
        assertRejected("no primary testnet", baseline().copy(requiresPrimary = true, networks = listOf(mainnet)))
        assertRejected("no primary mainnet", baseline().copy(requiresPrimary = true, networks = listOf(testnet)))
        ChainRegistry(listOf(FakeApp(baseline().copy(requiresPrimary = true, networks = listOf(testnet, mainnet)))))
        // An app that does not require one may have none, as Bitcoin and Algorand do.
        ChainRegistry(listOf(FakeApp(baseline())))
    }

    @Test
    fun rejectsAnUnsupportedApiVersionAndAnUndeclaredNamespace() {
        assertRejected("apiVersion", baseline().copy(apiVersion = 2))
        assertRejected("namespace", baseline().copy(namespaces = setOf("other")))
    }

    @Test
    fun rejectsKeyPathsThatBreakRulesOneToFour() {
        assertRejected("rule 1", withKeys(scope("m/44/283/0/0")))
        assertRejected("rule 2", withKeys(scope("m/44'/60'/*'/0/0")))
        assertRejected("rule 2", withKeys(scope("m/44'/283/*'/0/0")))
        assertRejected("rule 3", withKeys(scope("m/*'/283'/0'")))
        assertRejected("rule 4", withKeys(scope("m/44'/283'/*'/0/0", Curve.ED25519_SLIP10)))
    }

    @Test
    fun acceptsHardenedSlip10PathsAndNonHardenedBip32Paths() {
        ChainRegistry(listOf(FakeApp(withKeys(scope("m/44'/283'/0'/0'", Curve.ED25519_SLIP10)))))
        ChainRegistry(listOf(FakeApp(withKeys(scope("m/44'/283'/*'/0/0", Curve.ED25519_BIP32)))))
    }

    @Test
    fun rejectsOverlappingKeysOnOneCurveUnlessTheAppsLinkThem() {
        val first = baseline()
        // Distinct UR types, so the only rule this pair can break is the shared key rule.
        val second = baseline(id = "second", chain = "other:1", namespace = "other").copy(
            requestTypes = listOf(UrTypeSpec("other-sign-request", Direction.REQUEST, 1..1)),
            responseTypes = listOf(UrTypeSpec("other-signature", Direction.RESPONSE, 1..1)),
        )
        assertRejectedPair("rule 5", first, second)

        ChainRegistry(listOf(FakeApp(first), FakeApp(second.copy(sharesKeysWith = setOf(first.id)))))
        // A different branch of the same coin type does not overlap.
        ChainRegistry(listOf(FakeApp(first), FakeApp(second.copy(keys = listOf(scope("m/44'/283'/0'/1/0"))))))
        // The same path on another curve does not overlap.
        ChainRegistry(
            listOf(FakeApp(first), FakeApp(second.copy(keys = listOf(scope("m/44'/283'/*'/0/0", Curve.SECP256K1))))),
        )
    }

    @Test
    fun rejectsTwoPrimaryNetworksOnOneSide() {
        val testnets = listOf(network("testns:1").copy(primary = true), network("testns:2").copy(primary = true))
        assertRejected("more than one primary testnet", baseline().copy(networks = testnets))
        assertRejected("more than one primary mainnet", baseline().copy(networks = testnets.map { it.copy(testnet = false) }))
    }

    @Test
    fun rejectsANativeAssetOnAnotherChainOrOutOfRangeDecimals() {
        val onOtherChain = AssetSpec.Native(AssetId("testns:1/slip44:283"), ChainId("testns:2"), symbol = "TST", decimals = 6)
        assertRejected("native asset", baseline().copy(networks = listOf(network("testns:1").copy(nativeAsset = onOtherChain))))
        val tooManyDecimals = AssetSpec.Native(AssetId("testns:1/slip44:283"), ChainId("testns:1"), symbol = "TST", decimals = 40)
        assertRejected("decimals", baseline().copy(networks = listOf(network("testns:1").copy(nativeAsset = tooManyDecimals))))
    }

    @Test
    fun rejectsBadFieldSpecs() {
        val note = field("algorand.note")
        assertRejected("duplicate field key", baseline().copy(fields = listOf(note, note)))
        assertRejected("has no label", baseline().copy(fields = listOf(note.copy(label = " "))))
        assertRejected(
            "fixedLength",
            baseline().copy(
                fields = listOf(FieldSpec(FieldKey("algorand.lease"), FieldKind.MemoBytes(8, 32), label = "Lease")),
            ),
        )
    }

    @Test
    fun rejectsMalformedIdentifiersAndPaths() {
        assertFailsWith<IllegalArgumentException> { AppId("Algorand") }
        assertFailsWith<IllegalArgumentException> { ChainId("algorand") }
        assertFailsWith<IllegalArgumentException> { AssetId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k/283") }
        assertFailsWith<IllegalArgumentException> { FieldKey("Algorand.note") }
        assertFailsWith<IllegalArgumentException> { PathTemplate("44'/283'") }
        assertFailsWith<IllegalArgumentException> { PathTemplate("m/44'/4294967296'") }
    }

    private class FakeApp(override val manifest: ChainManifest) : ChainApp {
        override val addresses: AddressCodec get() = error("the registry does not read addresses")
    }

    private fun assertRejected(expected: String, manifest: ChainManifest) {
        val error = assertFailsWith<IllegalArgumentException> { ChainRegistry(listOf(FakeApp(manifest))) }
        assertContains(error.message.orEmpty(), expected)
    }

    private fun assertRejectedPair(expected: String, first: ChainManifest, second: ChainManifest) {
        val error = assertFailsWith<IllegalArgumentException> {
            ChainRegistry(listOf(FakeApp(first), FakeApp(second)))
        }
        assertContains(error.message.orEmpty(), expected)
    }

    private fun baseline(
        id: String = "testapp",
        namespace: String = "testns",
        chain: String = "testns:1",
    ) = ChainManifest(
        id = AppId(id),
        displayName = "Test app",
        apiVersion = AIRVAULT_API_VERSION,
        appVersion = "1.0.0",
        namespaces = setOf(namespace),
        networks = listOf(network(chain)),
        keys = listOf(scope("m/44'/283'/*'/0/0")),
        capabilities = emptySet(),
        requestTypes = listOf(UrTypeSpec("test-sign-request", Direction.REQUEST, 1..1)),
        responseTypes = listOf(UrTypeSpec("test-signature", Direction.RESPONSE, 1..1)),
        fields = emptyList(),
    )

    private fun withKeys(vararg scopes: KeyScope): ChainManifest = baseline().copy(keys = scopes.toList())

    private fun network(chain: String) = NetworkSpec(
        chainId = ChainId(chain),
        name = "Test network",
        testnet = true,
        nativeAsset = AssetSpec.Native(AssetId("$chain/slip44:283"), ChainId(chain), symbol = "TST", decimals = 6),
        slip44 = 283,
    )

    private fun scope(path: String, curve: Curve = Curve.ED25519_BIP32) =
        KeyScope(curve, listOf(PathTemplate(path)), purpose = "test keys")

    private fun field(key: String) = FieldSpec(FieldKey(key), FieldKind.MemoBytes(maxBytes = 1024), label = "Note")
}
