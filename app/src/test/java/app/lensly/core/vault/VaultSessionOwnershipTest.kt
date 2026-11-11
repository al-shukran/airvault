package app.lensly.core.vault

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Two sessions over one vault folder, with different keys, as the real vault and the decoy vault are. Each sees and changes
 * only the files its own key opens.
 */
class VaultSessionOwnershipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val realKey = ByteArray(32) { (it * 3 + 1).toByte() }
    private val decoyKey = ByteArray(32) { (it * 5 + 7).toByte() }
    private lateinit var layout: VaultLayout

    @Before
    fun setUp() {
        layout = VaultLayout(tmp.root)
    }

    @Test
    fun aSessionIsTheRealVaultUnlessItSaysItIsTheDecoy() {
        val real = open(realKey)
        val decoy = open(decoyKey, decoy = true)

        assertFalse(real.isDecoy)
        assertTrue(decoy.isDecoy)
        real.close()
        decoy.close()
    }

    @Test
    fun eachSessionListsOnlyItsOwnWallets() {
        val real = open(realKey)
        val decoy = open(decoyKey, decoy = true)
        val realWallet = real.createWallet()
        val decoyWallet = decoy.createWallet()

        assertEquals(listOf(realWallet), real.listWallets())
        assertEquals(listOf(decoyWallet), decoy.listWallets())
        real.close()
        decoy.close()
    }

    @Test
    fun aSessionCannotDeleteTheOthersWallet() {
        val real = open(realKey)
        val decoy = open(decoyKey, decoy = true)
        val realWallet = real.createWallet()
        real.putRecord(realWallet, "secret", byteArrayOf(4, 5))

        decoy.deleteWallet(realWallet)

        assertEquals(listOf(realWallet), real.listWallets())
        assertContentEquals(byteArrayOf(4, 5), real.getRecord(realWallet, "secret"))
        real.close()
        decoy.close()
    }

    @Test
    fun aSessionCannotWriteIntoTheOthersWallet() {
        val real = open(realKey)
        val decoy = open(decoyKey, decoy = true)
        val realWallet = real.createWallet()

        assertFailsWith<VaultException> { decoy.putRecord(realWallet, "secret", byteArrayOf(1)) }
        assertFailsWith<VaultException> { decoy.putRecords(realWallet, mapOf("secret" to byteArrayOf(1)), emptyList()) }
        real.close()
        decoy.close()
    }

    @Test
    fun vaultRecordsOfTwoKeysDoNotCollide() {
        val real = open(realKey)
        val decoy = open(decoyKey, decoy = true)
        real.putVaultRecord("settings", byteArrayOf(1))
        decoy.putVaultRecord("settings", byteArrayOf(2))

        assertContentEquals(byteArrayOf(1), real.getVaultRecord("settings"))
        assertContentEquals(byteArrayOf(2), decoy.getVaultRecord("settings"))
        real.close()
        decoy.close()
    }

    @Test
    fun aWalletOfTheOtherVaultIsNotAWalletHere() {
        val real = open(realKey)
        val decoy = open(decoyKey, decoy = true)
        val realWallet = real.createWallet()

        assertFalse(decoy.listWallets().contains(realWallet))
        assertEquals(null, decoy.getRecord(realWallet, "secret"))
        real.close()
        decoy.close()
    }

    private fun open(key: ByteArray, decoy: Boolean = false): VaultSession =
        VaultSession(layout, key.copyOf(), isDecoy = decoy)
}
