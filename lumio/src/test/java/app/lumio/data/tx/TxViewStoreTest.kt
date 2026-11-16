package app.lumio.data.tx

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import org.junit.Test
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** The sort the Transactions tab remembers: a new vault opens newest first, and the last sort the owner chose opens again. */
class TxViewStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var session: VaultSession

    @Before
    fun open() {
        session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun aNewVaultOpensNewestFirst() {
        assertEquals(TxViewStore.Sort(TxSort.TIMESTAMP, descending = true), TxViewStore.read(session))
    }

    @Test
    fun theLastSortIsRemembered() {
        TxViewStore.write(session, TxViewStore.Sort(TxSort.AMOUNT, descending = false))
        assertEquals(TxViewStore.Sort(TxSort.AMOUNT, descending = false), TxViewStore.read(session))
    }

    @Test
    fun everySortKeyAndOrderRoundTrips() {
        for (key in TxSort.entries) {
            for (descending in listOf(true, false)) {
                TxViewStore.write(session, TxViewStore.Sort(key, descending))
                assertEquals(TxViewStore.Sort(key, descending), TxViewStore.read(session))
            }
        }
    }

    @Test
    fun aRecordWithAnUnknownKeyReadsAsTheDefaultOrder() {
        session.putVaultRecord(TxViewStore.RECORD, "NOT_A_KEY|asc".encodeToByteArray())
        assertEquals(TxViewStore.Sort(TxSort.TIMESTAMP, descending = false), TxViewStore.read(session))
    }
}
