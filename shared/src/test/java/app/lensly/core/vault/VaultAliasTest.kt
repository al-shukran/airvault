package app.lensly.core.vault

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
}
