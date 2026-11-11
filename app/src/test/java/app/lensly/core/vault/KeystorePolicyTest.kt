package app.lensly.core.vault

import kotlin.test.assertEquals
import org.junit.Test

class KeystorePolicyTest {

    @Test
    fun aHardwareBackedKeystoreIsAllowedInAReleaseBuild() {
        assertEquals(
            KeystoreVerdict.HardwareBacked,
            KeystorePolicy.verdict(hardwareBacked = true, isDebugBuild = false),
        )
    }

    @Test
    fun aHardwareBackedKeystoreIsAllowedInADebugBuild() {
        assertEquals(
            KeystoreVerdict.HardwareBacked,
            KeystorePolicy.verdict(hardwareBacked = true, isDebugBuild = true),
        )
    }

    @Test
    fun aSoftwareKeystoreIsRefusedInAReleaseBuild() {
        assertEquals(
            KeystoreVerdict.Refused,
            KeystorePolicy.verdict(hardwareBacked = false, isDebugBuild = false),
        )
    }

    @Test
    fun aSoftwareKeystoreIsAllowedOnlyInADebugBuild() {
        assertEquals(
            KeystoreVerdict.SoftwareInDebugOnly,
            KeystorePolicy.verdict(hardwareBacked = false, isDebugBuild = true),
        )
    }
}
