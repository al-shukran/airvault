package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val USDC_LINEA = "0x176211869ca2b568f2a7d4ee941e073a821ee1ff"

/** The reload rules of [RegistryCatalog]: an accepted package counts at once, and a refused one changes nothing. */
class RegistryCatalogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = listOf(TestPackages.PUBLIC_KEY)
    private val baked: Registry = RegistryLoader.load { path ->
        File("src/main/assets", path).takeIf { it.isFile }?.readText()
    }

    private fun catalog(dir: File = folder.root.resolve("registry")): RegistryCatalog = RegistryCatalog(baked, dir, keys)

    @Test
    fun withoutAnInstalledPackageTheCurrentCatalogIsTheBakedOne() {
        val current = catalog().current()

        assertEquals(baked.networks(), current.networks())
        assertEquals(baked.tokens(), current.tokens())
    }

    @Test
    fun anAcceptedPackageIsInEffectAtOnceWithoutARestart() {
        val catalog = catalog()
        assertNull(catalog.current().network(4242L))

        catalog.install(TestPackages.signed(TestPackages.payload())).getOrThrow()

        assertEquals("Linea", assertNotNull(catalog.current().network(4242L)).name)
        assertEquals("USDC", assertNotNull(catalog.current().token(4242L, USDC_LINEA)).symbol)
    }

    @Test
    fun aTamperedPackageLeavesTheCurrentCatalogUnchanged() {
        val catalog = catalog()
        val before = catalog.current()
        val tampered = TestPackages.signed(TestPackages.payload())
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()

        assertIs<RegistryUpdateException.BadSignature>(catalog.install(tampered).exceptionOrNull())

        assertEquals(before.networks(), catalog.current().networks())
        assertEquals(before.tokens(), catalog.current().tokens())
    }

    @Test
    fun anOlderPackageIsRefusedAndTheInstalledOneStaysInEffect() {
        val catalog = catalog()
        catalog.install(TestPackages.signed(TestPackages.payload(sequence = 2))).getOrThrow()

        val refusal = catalog.install(TestPackages.signed(TestPackages.payload(sequence = 1)))

        assertIs<RegistryUpdateException.NotNewer>(refusal.exceptionOrNull())
        assertEquals("Linea", assertNotNull(catalog.current().network(4242L)).name)
    }

    @Test
    fun aCatalogCreatedAfterAnInstallSeesThePackageAsAfterARestart() {
        val dir = folder.root.resolve("registry")
        catalog(dir).install(TestPackages.signed(TestPackages.payload())).getOrThrow()

        val restarted = catalog(dir).current()

        assertEquals("Linea", assertNotNull(restarted.network(4242L)).name)
        assertEquals("USDC", assertNotNull(restarted.token(4242L, USDC_LINEA)).symbol)
    }
}
