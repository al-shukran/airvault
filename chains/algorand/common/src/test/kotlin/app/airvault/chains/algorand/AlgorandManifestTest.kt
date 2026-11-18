package app.airvault.chains.algorand

import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainApp
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.ChainRegistry
import app.airvault.core.model.Curve
import app.airvault.core.model.PathTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AlgorandManifestTest {
    private val app = object : ChainApp {
        override val manifest: ChainManifest = algorandManifest
        override val addresses: AddressCodec get() = error("the registry does not read addresses")
    }

    @Test
    fun passesTheRegistryAndServesBothNetworks() {
        val registry = ChainRegistry(listOf(app))

        assertNotNull(registry.byChain(ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")))
        assertNotNull(registry.byChain(ChainId("algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDe")))
        assertEquals(listOf(283), registry.networks().map { it.slip44 }.distinct())
    }

    @Test
    fun declaresTheBip32Ed25519KeyScopeUrTypesAndFields() {
        val scope = algorandManifest.keys.single()
        assertEquals(Curve.ED25519_BIP32, scope.curve)
        assertEquals(listOf(PathTemplate("m/44'/283'/*'/0/0")), scope.pathPrefixes)
        assertEquals(listOf("algo-sign-request"), algorandManifest.requestTypes.map { it.type })
        assertEquals(listOf("algo-signature"), algorandManifest.responseTypes.map { it.type })
        assertEquals(
            listOf("algorand.note", "algorand.closeTo", "algorand.rekeyTo", "algorand.lease"),
            algorandManifest.fields.map { it.key.value },
        )
    }
}
