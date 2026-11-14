package app.lensly.app

import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultKey = ByteArray(32) { (it * 5 + 3).toByte() }
    private val words = List(23) { "abandon" } + "art"

    // A session zeroes the key array it is given when it closes, so each session gets its own copy.
    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, vaultKey.copyOf())

    /** The settings record as the store writes it, with a chosen version and account count. */
    private fun record(version: Int, accounts: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(version)
            out.writeInt(accounts)
        }
        return buffer.toByteArray()
    }

    @Test
    fun aVaultWithNoSavedSettingsReadsTheDefaultBulkCap() {
        val session = open()

        assertEquals(BulkCap.DEFAULT, SettingsStore(session).load().bulkCap)
        session.close()
    }

    @Test
    fun aSavedBulkCapIsReadBackAfterTheVaultReopens() {
        val first = open()
        SettingsStore(first).save(AppSettings(bulkCap = BulkCap.TWENTY))
        first.close()

        val reopened = open()
        assertEquals(BulkCap.TWENTY, SettingsStore(reopened).load().bulkCap)
        reopened.close()
    }

    @Test
    fun theLastSavedBulkCapWins() {
        val session = open()
        val store = SettingsStore(session)
        store.save(AppSettings(BulkCap.TWENTY))
        store.save(AppSettings(BulkCap.HUNDRED))

        assertEquals(BulkCap.HUNDRED, store.load().bulkCap)
        session.close()
    }

    @Test
    fun aLegacyThousandCapReadsAsOneHundred() {
        // Before this change the largest cap was 1,000. A vault that saved it reads the largest cap offered now.
        val session = open()
        session.putVaultRecord("settings", record(version = 1, accounts = 1_000))

        assertEquals(BulkCap.HUNDRED, SettingsStore(session).load().bulkCap)
        session.close()
    }

    @Test
    fun everyOfferedBulkCapRoundTrips() {
        for (cap in BulkCap.entries) {
            val session = open()
            SettingsStore(session).save(AppSettings(cap))
            assertEquals(cap, SettingsStore(session).load().bulkCap)
            session.close()
        }
    }

    @Test
    fun anUnofferedCapCountReadsAsOneHundred() {
        // A count the app does not offer, such as 500, reads as the largest cap offered, not as the default.
        val session = open()
        session.putVaultRecord("settings", record(version = 1, accounts = 500))

        assertEquals(BulkCap.HUNDRED, SettingsStore(session).load().bulkCap)
        session.close()
    }

    /** The settings record as version 2 writes it: the account count, then the Proxy Policy code. */
    private fun recordV2(accounts: Int, policyCode: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(2)
            out.writeInt(accounts)
            out.writeByte(policyCode)
        }
        return buffer.toByteArray()
    }

    @Test
    fun anUnknownSettingsVersionReadsAsTheDefault() {
        val session = open()
        session.putVaultRecord("settings", record(version = 3, accounts = 1_000))

        assertEquals(BulkCap.DEFAULT, SettingsStore(session).load().bulkCap)
        session.close()
    }

    @Test
    fun aSavedProxyPolicyIsReadBackAfterTheVaultReopens() {
        val first = open()
        SettingsStore(first).save(AppSettings(BulkCap.TWENTY, ProxyPolicy.WARN_AND_CONFIRM))
        first.close()

        val reopened = open()
        assertEquals(ProxyPolicy.WARN_AND_CONFIRM, SettingsStore(reopened).load().proxyPolicy)
        assertEquals(BulkCap.TWENTY, SettingsStore(reopened).load().bulkCap)
        reopened.close()
    }

    @Test
    fun everyProxyPolicyRoundTrips() {
        val session = open()
        for (policy in ProxyPolicy.entries) {
            SettingsStore(session).save(AppSettings(proxyPolicy = policy))
            assertEquals(policy, SettingsStore(session).load().proxyPolicy)
        }
        session.close()
    }

    @Test
    fun aVersionOneRecordReadsWithTheDefaultProxyPolicy() {
        val session = open()
        session.putVaultRecord("settings", record(version = 1, accounts = BulkCap.TWENTY.accounts))

        val loaded = SettingsStore(session).load()
        assertEquals(BulkCap.TWENTY, loaded.bulkCap)
        assertEquals(ProxyPolicy.DEFAULT, loaded.proxyPolicy)
        session.close()
    }

    @Test
    fun anUnknownProxyPolicyCodeReadsAsTheDefault() {
        val session = open()
        session.putVaultRecord("settings", recordV2(accounts = BulkCap.TWENTY.accounts, policyCode = 9))

        val loaded = SettingsStore(session).load()
        assertEquals(BulkCap.TWENTY, loaded.bulkCap)
        assertEquals(ProxyPolicy.DEFAULT, loaded.proxyPolicy)
        session.close()
    }

    @Test
    fun aTruncatedSettingsRecordReadsAsTheDefault() {
        val session = open()
        // Version 1, then no account count.
        session.putVaultRecord("settings", byteArrayOf(1, 0))

        assertEquals(BulkCap.DEFAULT, SettingsStore(session).load().bulkCap)
        session.close()
    }

    @Test
    fun aDamagedSettingsRecordReadsAsTheDefault() {
        val session = open()
        SettingsStore(session).save(AppSettings(BulkCap.TWENTY))
        val file = session.vaultRecordFileForTesting("settings")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        // The record is sealed, so the flipped byte fails authentication and reads as the default, not as another cap.
        assertEquals(BulkCap.DEFAULT, SettingsStore(session).load().bulkCap)
        session.close()
    }

    @Test
    fun aClosedSessionStillThrowsWhenSettingsAreLoaded() {
        // The fallback to the default covers damaged records only. A closed session is a bug and still throws.
        val session = open()
        session.close()

        assertFailsWith<IllegalStateException> { SettingsStore(session).load() }
    }

    @Test
    fun theBulkRunEnforcesTheSavedCap() {
        // The bulk run reads the cap from the saved setting, as the bulk screen does, and not from a constant.
        val session = open()
        val wallets = WalletStore(session)
        val id = wallets.create(name = "Test", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)

        SettingsStore(session).save(AppSettings(BulkCap.TWENTY))
        val twenty = SettingsStore(session).load().bulkCap.accounts
        assertFailsWith<IllegalArgumentException> { wallets.addAccounts(id, 21, twenty) }
        assertEquals(1, wallets.meta(id)?.accountCount)

        SettingsStore(session).save(AppSettings(BulkCap.HUNDRED))
        val hundred = SettingsStore(session).load().bulkCap.accounts
        assertEquals(101, assertNotNull(wallets.addAccounts(id, 100, hundred)).accountCount)
        session.close()
    }
}
