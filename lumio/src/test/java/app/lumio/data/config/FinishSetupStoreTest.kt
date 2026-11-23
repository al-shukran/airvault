package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Finish Setting Up checklist: nothing ticked before any step, ticks kept across a reopen, and complete once every item is ticked. */
class FinishSetupStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    // A session zeroes the key it is given when it closes, so each session gets its own copy.
    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 11 + 5).toByte() })

    @Test
    fun nothingIsTickedBeforeAnyStepIsDone() {
        val session = open()

        val setup = FinishSetupStore.read(session)

        assertEquals(FinishSetup(), setup)
        assertEquals(FinishItem.entries, setup.open)
        assertFalse(setup.complete)
        session.close()
    }

    @Test
    fun aTickIsReadBackAfterTheVaultReopens() {
        val session = open()
        FinishSetupStore.tick(session, FinishItem.PAIR)
        session.close()

        val reopened = open()
        assertEquals(setOf(FinishItem.PAIR), FinishSetupStore.read(reopened).done)
        assertEquals(listOf(FinishItem.DURESS, FinishItem.DEVICE_PROTECTION), FinishSetupStore.read(reopened).open)
        reopened.close()
    }

    @Test
    fun tickingTheSameItemTwiceKeepsOneTick() {
        val session = open()

        FinishSetupStore.tick(session, FinishItem.DEVICE_PROTECTION)
        FinishSetupStore.tick(session, FinishItem.DEVICE_PROTECTION)

        assertEquals(setOf(FinishItem.DEVICE_PROTECTION), FinishSetupStore.read(session).done)
        session.close()
    }

    @Test
    fun theChecklistIsCompleteOnceEveryItemIsTicked() {
        val session = open()

        FinishItem.entries.forEach { FinishSetupStore.tick(session, it) }

        val setup = FinishSetupStore.read(session)
        assertTrue(setup.complete)
        assertTrue(setup.open.isEmpty())
        session.close()
    }

    @Test
    fun aDamagedRecordReadsAsNothingTicked() {
        val session = open()
        session.putVaultRecord(FinishSetupStore.RECORD, "not json".encodeToByteArray())

        assertEquals(FinishSetup(), FinishSetupStore.read(session))
        session.close()
    }
}
