package app.lensly.core.registry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.core.vault.androidKeystore
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Android Keystore holds a revocation marker per key ID, once however often it is anchored, and lists it afterwards.
 * It only touches the marker of its own key ID: the app's vault and registry keys are never wiped (that [VaultKeystore.deleteAll]
 * leaves the markers is a unit test, VaultAliasTest).
 */
@RunWith(AndroidJUnit4::class)
class RegistryRevocationAnchorDeviceTest {
    private val keys = androidKeystore(InstrumentationRegistry.getInstrumentation().targetContext)

    // Revocations are permanent in the app, so the test's own marker is removed straight from the Keystore, before and after.
    @Before
    @After
    fun removeMarker() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("k10.$ID")
    }

    @Test
    fun anAnchoredRevocationIsListedOnceAndKeptByAFreshHandle() {
        keys.anchorRegistryRevocation(ID)
        keys.anchorRegistryRevocation(ID)

        assertEquals(1, keys.registryRevokedKeyIds().count { it == ID })
        assertTrue(ID in androidKeystore(InstrumentationRegistry.getInstrumentation().targetContext).registryRevokedKeyIds())
    }

    private companion object {
        const val ID = "00000000deadbeef"
    }
}
