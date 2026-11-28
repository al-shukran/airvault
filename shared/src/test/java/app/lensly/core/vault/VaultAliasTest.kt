package app.lensly.core.vault

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** [VaultKeystore.deleteAll] erases the aliases this names, so the registry's keys, which outlive the vault, must not be among them. */
class VaultAliasTest {
    @Test
    fun theVaultsKeysAreErased() {
        listOf("k1", "k2", "k3", "k4", "k5", "k6", "k7.4", "k8.abc").forEach { assertTrue(VaultKeystore.isVaultAlias(it), it) }
    }

    @Test
    fun theRegistrysFloorAndRevocationKeysAreLeft() {
        listOf("k9.12", "k10.00000000deadbeef").forEach { assertFalse(VaultKeystore.isVaultAlias(it), it) }
    }

    private val stored = listOf("k9.9223372036854775807", "k9.10", "k9.5", "k9.7", "k10.00000000deadbeef", "k8.abc", "k7.4", "k2", "k9.abc")

    @Test
    fun theRegistrysFloorKeysGoLowestFirstWhateverOrderTheKeystoreListsThem() {
        val expected = listOf("k9.abc", "k9.5", "k9.10", "k9.9223372036854775807")
        assertEquals(expected, VaultKeystore.registryAliasesToDelete(stored, keepSeq = 7))
        assertEquals(expected, VaultKeystore.registryAliasesToDelete(stored.reversed(), keepSeq = 7))
        assertEquals(expected, VaultKeystore.registryAliasesToDelete(stored.shuffled(java.util.Random(1)), keepSeq = 7))
    }

    @Test
    fun withNoKeyToKeepEveryRegistryFloorKeyGoes() {
        assertEquals(
            listOf("k9.abc", "k9.5", "k9.7", "k9.10", "k9.9223372036854775807"),
            VaultKeystore.registryAliasesToDelete(stored, keepSeq = null),
        )
    }
}
