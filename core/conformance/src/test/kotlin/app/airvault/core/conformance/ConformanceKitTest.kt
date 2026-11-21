package app.airvault.core.conformance

import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.algorand.signer.AlgorandSigner
import app.airvault.chains.bitcoin.signer.BitcoinSigner
import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.chains.solana.signer.SolanaSigner
import app.airvault.chains.template.signer.TemplateSigner
import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainApp
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Curve
import app.airvault.core.model.Direction
import app.airvault.core.model.FieldKey
import app.airvault.core.model.FieldKind
import app.airvault.core.model.FieldSpec
import app.airvault.core.model.KeyScope
import app.airvault.core.model.PathTemplate
import app.airvault.core.model.UrTypeSpec
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.KeyScopeViolation
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.ScopedKeyAccess
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConformanceKitTest {
    @Test
    fun everySignerPassesTheManifestChecks() {
        for (app in listOf(EvmSigner, BitcoinSigner(), SolanaSigner, AlgorandSigner)) {
            assertEquals(emptyList(), checkManifest(app), "${app.manifest.id}")
        }
    }

    @Test
    fun everySignerPassesTheKeyScopeChecksThroughTheCoreEnforcer() {
        for (app in listOf(EvmSigner, BitcoinSigner(), SolanaSigner, AlgorandSigner)) {
            val keys = ScopedKeyAccess(app.manifest.keys, DerivingKeys)
            assertEquals(emptyList(), checkKeyScopes(app, keys), "${app.manifest.id}")
        }
    }

    @Test
    fun keyAccessWithoutAScopeCheckFailsEachOutsidePath() {
        // Algorand's prefix has five levels: five hardening flips, one other coin type, and two other curves.
        val failures = checkKeyScopes(AlgorandSigner, DerivingKeys)

        assertEquals(8, failures.size)
        assertTrue(failures.all { it.check == "key scope" && it.message.endsWith("expected KeyScopeViolation") })
    }

    @Test
    fun keyAccessThatRefusesEverythingFailsTheInsideCheck() {
        val refusingKeys = object : KeyAccess by DerivingKeys {
            override fun publicKey(curve: Curve, path: Path): PublicKey = throw KeyScopeViolation(curve, path)
        }

        val failures = checkKeyScopes(AlgorandSigner, refusingKeys)

        assertEquals(1, failures.size)
        assertContains(failures.single().message, "was refused")
    }

    @Test
    fun theTemplateFailsTheKitOnlyAtItsTodoStubs() {
        // The template's manifest is a TODO, so the manifest check stops there and the key-scope check stops on the same read.
        val manifest = assertFailsWith<NotImplementedError> { checkManifest(TemplateSigner) }
        assertContains(manifest.message.orEmpty(), "template:")
        val keys = assertFailsWith<NotImplementedError> { checkKeyScopes(TemplateSigner, DerivingKeys) }
        assertContains(keys.message.orEmpty(), "template:")
    }

    @Test
    fun brokenManifestsFailTheManifestCheck() {
        val base = algorandManifest
        val fixtures = mapOf(
            "EVM without a primary" to evmManifest.copy(networks = evmManifest.networks.map { it.copy(primary = false) }),
            "apiVersion" to base.copy(apiVersion = AIRVAULT_API_VERSION + 1),
            "namespace" to base.copy(namespaces = setOf("other")),
            "coin type" to keyed(PathTemplate("m/44'/0'/*'/0/0")),
            "unhardened coin" to keyed(PathTemplate("m/44'/283/*'/0/0")),
            "hardened level" to keyed(PathTemplate("m/44/283/0/0/0")),
            "wildcard above the account" to keyed(PathTemplate("m/*'/283'/0'")),
            "every level hardened" to base.copy(
                keys = listOf(KeyScope(Curve.ED25519_SLIP10, listOf(PathTemplate("m/44'/283'/0'/0/0")), "test")),
            ),
            "field spec" to base.copy(
                fields = listOf(
                    FieldSpec(FieldKey("algorand.lease"), FieldKind.MemoBytes(maxBytes = 4, fixedLength = 8), "Lease"),
                ),
            ),
            "field label" to base.copy(
                fields = listOf(FieldSpec(FieldKey("algorand.note"), FieldKind.MemoBytes(maxBytes = 4), " ")),
            ),
            "duplicate UR type" to base.copy(
                responseTypes = base.responseTypes + UrTypeSpec("algo-sign-request", Direction.RESPONSE),
            ),
            "malformed UR type" to base.copy(
                requestTypes = listOf(UrTypeSpec("algo sign", Direction.REQUEST)),
            ),
        )
        val expected = mapOf(
            "EVM without a primary" to "no primary testnet",
            "apiVersion" to "apiVersion",
            "namespace" to "namespace",
            "coin type" to "coin-type level must be hardened",
            "unhardened coin" to "coin-type level must be hardened",
            "hardened level" to "no hardened level",
            "wildcard above the account" to "wildcard is allowed only",
            "every level hardened" to "must be hardened at every level",
            "field spec" to "fixedLength 8 above maxBytes 4",
            "field label" to "has no label",
            "duplicate UR type" to "duplicate UR type",
            "malformed UR type" to "malformed",
        )
        for ((name, manifest) in fixtures) {
            val failures = checkManifest(appWith(manifest))
            assertEquals(1, failures.size, name)
            assertEquals("manifest", failures.single().check, name)
            assertContains(failures.single().message, expected.getValue(name), message = name)
        }
    }

    /** The Algorand manifest with its one key scope replaced by [prefix] on the same curve. */
    private fun keyed(prefix: PathTemplate): ChainManifest = algorandManifest.copy(
        keys = listOf(KeyScope(Curve.ED25519_BIP32, listOf(prefix), "test")),
    )

    private fun appWith(manifest: ChainManifest): ChainApp = object : ChainApp {
        override val manifest: ChainManifest = manifest
        override val addresses: AddressCodec get() = error("the kit does not read addresses")
    }
}

/** A key access that derives a placeholder key for any path, so no scope check runs. */
private object DerivingKeys : KeyAccess {
    override fun publicKey(curve: Curve, path: Path): PublicKey = PublicKey(curve, ByteArray(32))

    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature =
        TODO("the kit derives public keys only")

    override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray =
        TODO("the kit derives public keys only")

    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray =
        TODO("the kit derives public keys only")

    override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T =
        TODO("the kit derives public keys only")
}
