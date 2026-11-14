package app.lensly.core.vault

import kotlin.test.assertEquals
import org.junit.Test

class KeystorePolicyTest {

    @Test
    fun aHardwareBackedKeystoreIsAllowedInAReleaseBuild() {
        assertEquals(
            KeystoreVerdict.HardwareBacked,
            KeystorePolicy.verdict(hardwareBacked = true, isDebugBuild = false, isEmulator = false),
        )
    }

    @Test
    fun aHardwareBackedKeystoreIsAllowedInADebugBuild() {
        assertEquals(
            KeystoreVerdict.HardwareBacked,
            KeystorePolicy.verdict(hardwareBacked = true, isDebugBuild = true, isEmulator = false),
        )
    }

    @Test
    fun aSoftwareKeystoreIsRefusedInAReleaseBuild() {
        assertEquals(
            KeystoreVerdict.Refused,
            KeystorePolicy.verdict(hardwareBacked = false, isDebugBuild = false, isEmulator = false),
        )
    }

    @Test
    fun aSoftwareKeystoreIsRefusedInAReleaseBuildOnAnEmulator() {
        assertEquals(
            KeystoreVerdict.Refused,
            KeystorePolicy.verdict(hardwareBacked = false, isDebugBuild = false, isEmulator = true),
        )
    }

    @Test
    fun aSoftwareKeystoreIsAllowedInADebugBuildOnAnEmulator() {
        assertEquals(
            KeystoreVerdict.SoftwareInDebugOnly,
            KeystorePolicy.verdict(hardwareBacked = false, isDebugBuild = true, isEmulator = true),
        )
    }

    @Test
    fun aSoftwareKeystoreIsRefusedInADebugBuildOnARealDevice() {
        assertEquals(
            KeystoreVerdict.Refused,
            KeystorePolicy.verdict(hardwareBacked = false, isDebugBuild = true, isEmulator = false),
        )
    }
}
