package app.lensly.core.registry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.core.vault.androidKeystore
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real Android Keystore holds a revocation marker per key ID, which a wipe of the vault's keys and of the registry floor keys leaves. */
@RunWith(AndroidJUnit4::class)
class RegistryRevocationAnchorDeviceTest {
    private val keys = androidKeystore(InstrumentationRegistry.getInstrumentation().targetContext)

    @After
    fun removeMarker() {
        // Revocations are permanent in the app; the test's own marker is removed straight from the Keystore.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("k10.$ID")
    }

    @Test
    fun anAnchoredRevocationIsListedStaysAfterTheOtherKeysAreWipedAndIsAnchoredOnce() {
        assertFalse(ID in keys.registryRevokedKeyIds())

        keys.anchorRegistryRevocation(ID)
        keys.anchorRegistryRevocation(ID)
        keys.deleteRegistryMacKeysExcept(null)
        keys.deleteAll()

        assertEquals(1, keys.registryRevokedKeyIds().count { it == ID })
        assertTrue(ID in keys.registryRevokedKeyIds())
    }

    private companion object {
        const val ID = "00000000deadbeef"
    }
}
