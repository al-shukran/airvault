package app.lensly.core.vault

import java.io.File
import java.security.ProviderException
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Each wallet folder has a Keystore key of its own. Deleting a wallet deletes that key, so the key file the folder kept cannot be opened
 * again, even with the Vault Key. Every folder has one key, wallet or unused slot alike, so the key count says nothing about the wallets.
 */
class WalletKeyShredTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, keys, clock, KDF)
    }

    @Test
    fun deletingAWalletDeletesItsFolderKeyAndItsKeyFileOpensNoMore() {
        create()
        val session = unlock(vault)
        val vaultKey = session.copyOfVaultKey()
        val wallet = session.createWallet()
        session.putRecord(wallet, WalletFolder.SECRET, byteArrayOf(1, 2, 3))
        val dir = layout.walletDir(wallet)
        val keyId = keyIdOf(wallet)
        val folderKey = assertNotNull(keys.folderKey(keyId))
        // A copy of the key file that survives on the flash, as an old block would.
        val keyFileBeforeDelete = File(dir, WALLET_KEY_FILE_NAME).readBytes()

        session.deleteWallet(wallet)

        assertNull(keys.folderKey(keyId), "The folder's Keystore key is still there after the delete.")
        assertFalse(session.listWallets().contains(wallet))
        // The surviving copy does not open with the Vault Key, because its folder key is gone.
        File(dir, WALLET_KEY_FILE_NAME).writeBytes(keyFileBeforeDelete)
        assertNull(WalletFolder.walletKeyOf(keys, dir, vaultKey, wallet))
        // Control: with the deleted key back, the same copy opens. So the delete is what makes the copy unreadable.
        keys.restoreFolderKey(keyId, folderKey)
        assertNotNull(WalletFolder.walletKeyOf(keys, dir, vaultKey, wallet))
        vaultKey.fill(0)
        session.close()
    }

    @Test
    fun wipeDeletesTheKeyOfEveryFolder() {
        create()
        val session = unlock(vault)
        repeat(3) { session.createWallet() }
        session.close()
        assertEquals(WALLET_SLOT_STEP, folderDirs().size)
        assertEquals(folderDirs().size, keys.folderKeyIds().size)

        vault.wipe()

        assertTrue(keys.folderKeyIds().isEmpty(), "Some folder keys survived the wipe.")
        assertFalse(layout.root.exists())
    }

    @Test
    fun everyFolderHasOneKeyAndTheFoldersStillLookAlike() {
        create()
        val real = unlock(vault)
        val wallet = real.createWallet()
        real.putRecord(wallet, WalletFolder.META, ByteArray(200) { 1 })
        val result = runBlocking {
            vault.setDuress(real, DURESS.toCharArray()) { decoy ->
                decoy.putRecord(decoy.createWallet(), WalletFolder.SECRET, ByteArray(1500) { 6 })
            }
        }
        assertEquals(DuressResult.Enabled, result)
        real.close()

        val reopened = unlock(vault)
        val folders = folderDirs()
        assertEquals(WALLET_SLOT_STEP, folders.size)
        // Each folder names one key, and that key is the only one of its kind in the Keystore.
        assertEquals(folders.map { keyIdOf(it.name) }.toSet(), keys.folderKeyIds().toSet())
        assertEquals(folders.size, keys.folderKeyIds().size)
        val shapes = folders.map { shapeOf(it) }
        shapes.forEach { assertEquals(shapes.first(), it) }
        reopened.close()
    }

    @Test
    fun aWalletTakingAnUnusedSlotKeepsTheKeyCount() {
        create()
        val session = unlock(vault)
        val before = keys.folderKeyIds().toSet()

        val wallet = session.createWallet()

        // The slot's own key is the one the new wallet's key file uses, so no key is created or removed.
        assertTrue(keyIdOf(wallet) in before)
        assertEquals(before, keys.folderKeyIds().toSet())
        session.close()
    }

    @Test
    fun aVaultFromTheEarlierLayoutIsUpgradedOnItsFirstUnlockAndItsWalletOpens() {
        create()
        val session = unlock(vault)
        val wallet = session.createWallet()
        session.putRecord(wallet, WalletFolder.SECRET, byteArrayOf(7, 8, 9))
        val vaultKey = session.copyOfVaultKey()
        val walletKey = assertNotNull(WalletFolder.walletKeyOf(keys, layout.walletDir(wallet), vaultKey, wallet))
        session.close()
        // The earlier layout: each key file is the vault-sealed key alone, and no folder has a Keystore key. An unused slot holds
        // random bytes.
        folderDirs().forEach { dir ->
            val inner = if (dir.name == wallet) {
                encodeBlob(Aead.seal(vaultKey, wallet.toByteArray(Charsets.UTF_8), walletKey))
            } else {
                Random.nextBytes(WalletFolder.VAULT_WRAPPED_KEY_BYTES)
            }
            File(dir, WALLET_KEY_FILE_NAME).writeBytes(inner)
        }
        keys.folderKeyIds().forEach { keys.deleteFolderKey(it) }

        val reopened = unlock(vault)

        assertEquals(listOf(wallet), reopened.listWallets())
        assertContentEquals(byteArrayOf(7, 8, 9), reopened.getRecord(wallet, WalletFolder.SECRET))
        folderDirs().forEach { assertEquals(WalletFolder.KEY_FILE_BYTES.toLong(), File(it, WALLET_KEY_FILE_NAME).length()) }
        assertEquals(folderDirs().size, keys.folderKeyIds().size)
        val shapes = folderDirs().map { shapeOf(it) }
        shapes.forEach { assertEquals(shapes.first(), it) }
        reopened.close()
    }

    @Test
    fun aDecoyWalletFromTheEarlierLayoutOpensAfterTheRealVaultIsUpgraded() {
        create()
        val real = unlock(vault)
        var decoyWallet = ""
        val result = runBlocking {
            vault.setDuress(real, DURESS.toCharArray()) { decoy ->
                decoyWallet = decoy.createWallet()
                decoy.putRecord(decoyWallet, WalletFolder.SECRET, byteArrayOf(5, 6))
            }
        }
        assertEquals(DuressResult.Enabled, result)
        val realKey = real.copyOfVaultKey()
        real.close()
        val decoy = unlock(vault, DURESS)
        val decoyKey = decoy.copyOfVaultKey()
        decoy.close()
        // The earlier layout for both vaults. A folder keeps the vault-sealed key of its owner, and an unused slot holds random bytes.
        folderDirs().forEach { dir ->
            val inner = listOf(realKey, decoyKey).firstNotNullOfOrNull { key ->
                WalletFolder.walletKeyOf(keys, dir, key, dir.name)?.let { walletKey ->
                    encodeBlob(Aead.seal(key, dir.name.toByteArray(Charsets.UTF_8), walletKey))
                }
            } ?: Random.nextBytes(WalletFolder.VAULT_WRAPPED_KEY_BYTES)
            File(dir, WALLET_KEY_FILE_NAME).writeBytes(inner)
        }
        keys.folderKeyIds().forEach { keys.deleteFolderKey(it) }

        // The real vault unlocks first and upgrades every folder, the decoy's included, without the Decoy Vault's key.
        val upgraded = unlock(vault)
        assertEquals(emptyList(), upgraded.listWallets())
        upgraded.close()

        val reopenedDecoy = unlock(vault, DURESS)
        assertEquals(listOf(decoyWallet), reopenedDecoy.listWallets())
        assertContentEquals(byteArrayOf(5, 6), reopenedDecoy.getRecord(decoyWallet, WalletFolder.SECRET))
        reopenedDecoy.close()
    }

    @Test
    fun aKeystoreErrorDuringDeleteStillRemovesTheWalletAndReports() {
        create()
        val flaky = DeleteFails(keys)
        val session = unlock(Vault(layout, flaky, clock, KDF))
        val gone = session.createWallet()
        val kept = session.createWallet()
        session.putRecord(gone, WalletFolder.SECRET, byteArrayOf(4))
        val goneKeyId = keyIdOf(gone)
        flaky.refuseDeletes = true

        assertFailsWith<VaultException> { session.deleteWallet(gone) }

        assertEquals(listOf(kept), session.listWallets())
        assertTrue(WalletFolder.isUnused(layout.walletDir(gone)))
        assertNotNull(keys.folderKey(goneKeyId), "The refused key should still be in the Keystore.")
        session.close()

        // The next unlock, with a Keystore that deletes again, removes the key that no folder names any more.
        val reopened = unlock(vault)
        assertNull(keys.folderKey(goneKeyId))
        assertEquals(listOf(kept), reopened.listWallets())
        assertEquals(folderDirs().size, keys.folderKeyIds().size)
        reopened.close()
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlock(on: Vault = vault, password: String = APP): VaultSession {
        val result = runBlocking { on.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private fun folderDirs(): List<File> = layout.wallets.listFiles().orEmpty().filter { it.isDirectory }

    /** The id of the Keystore key that wallet folder [walletId]'s key file names in clear. */
    private fun keyIdOf(walletId: String): String =
        String(File(layout.walletDir(walletId), WALLET_KEY_FILE_NAME).readBytes(), 1, 36, Charsets.US_ASCII)

    /** The sorted (file name, size) list of a folder. */
    private fun shapeOf(dir: File): List<Pair<String, Long>> =
        dir.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.length() }.sortedBy { it.first }

    /** A Keystore whose folder key deletions fail while [refuseDeletes] is set, as a Keystore error does. */
    private class DeleteFails(private val inner: KeystoreBackend) : KeystoreBackend by inner {
        var refuseDeletes = false

        override fun deleteFolderKey(keyId: String) {
            if (refuseDeletes) throw ProviderException("the Keystore refused to delete the key")
            inner.deleteFolderKey(keyId)
        }
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
