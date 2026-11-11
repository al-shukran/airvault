package app.lensly.app

import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.flow.ShredFlow
import app.lensly.ui.flow.ShredOutcome
import app.lensly.ui.flow.ShredPorts
import app.lensly.ui.flow.ShredStep
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val PASSWORD = "cobalt lantern marsh vivid tundra pepper"

/**
 * Delete Wallet on real storage: the flow drives [WalletStore.delete] on an open session, as the app's ports do. The
 * password check is answered by the test, since the vault needs an Android Context.
 */
class WalletDeleteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), ByteArray(32) { (it + 5).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    private fun newWallet(name: String, passphrase: String): String = store.create(
        name = name,
        deviceGenerated = false,
        words = words,
        passphrase = passphrase,
        nowMs = 1L,
    )

    /** A flow whose password check gives [check], and whose erase deletes [target] through the store. */
    private fun flowDeleting(target: String, check: PasswordCheck): ShredFlow = ShredFlow(
        object : ShredPorts {
            override suspend fun checkPassword(password: CharArray): PasswordCheck = check

            override suspend fun erase(): ShredOutcome {
                store.delete(target)
                return ShredOutcome.ERASED
            }
        },
    )

    @Test
    fun confirmingDeletesOnlyThatWallet() = runBlocking<Unit> {
        val kept = newWallet("KEPT", "one")
        val gone = newWallet("GONE", "two")
        val other = newWallet("OTHER", "three")
        val keptAddress = store.addresses(kept, 1)
        val otherAddress = store.addresses(other, 1)
        val flow = flowDeleting(gone, PasswordCheck.Accepted)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.confirm()

        assertEquals(ShredStep.Ended(ShredOutcome.ERASED), flow.state.value)
        assertNull(store.meta(gone))
        assertNull(store.secret(gone))
        assertFalse(File(VaultLayout(tmp.root).walletDir(gone), "pwk.bin").exists())
        assertEquals(setOf(kept, other), store.list().map { it.id }.toSet())
        assertEquals(keptAddress, store.addresses(kept, 1))
        assertEquals(otherAddress, store.addresses(other, 1))
        assertNotNull(store.secret(kept))
        assertNotNull(store.secret(other))
    }

    @Test
    fun aWrongPasswordLeavesEveryWalletInPlace() = runBlocking<Unit> {
        val first = newWallet("FIRST", "one")
        val second = newWallet("SECOND", "two")
        val flow = flowDeleting(first, PasswordCheck.Rejected)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.confirm()

        assertEquals(ShredStep.Password(busy = false), flow.state.value)
        assertEquals(setOf(first, second), store.list().map { it.id }.toSet())
        assertNotNull(store.secret(first))
    }
}
