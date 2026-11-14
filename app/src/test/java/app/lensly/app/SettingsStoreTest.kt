package app.lensly.app

import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.SigningSourcePolicy
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

    /** The settings record as version 3 writes it, with the shortest and the longest hide delay in milliseconds. */
    private fun recordV3(accounts: Int, policyCode: Int, shortestMs: Int, longestMs: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(3)
            out.writeInt(accounts)
            out.writeByte(policyCode)
            out.writeInt(shortestMs)
            out.writeInt(longestMs)
        }
        return buffer.toByteArray()
    }

    @Test
    fun aVaultWithNoSavedSettingsReadsTheDefaultHideDelay() {
        val session = open()
        assertEquals(HideDelay.DEFAULT, SettingsStore(session).load().hideDelay)
        session.close()
    }

    @Test
    fun aSavedHideDelayIsReadBackAfterTheVaultReopens() {
        val first = open()
        SettingsStore(first).save(AppSettings(hideDelay = HideDelay(250, 4_000)))
        first.close()

        val reopened = open()
        assertEquals(HideDelay(250, 4_000), SettingsStore(reopened).load().hideDelay)
        reopened.close()
    }

    @Test
    fun everyHideDelayAtItsEdgesRoundTrips() {
        for (range in listOf(HideDelay(0, 0), HideDelay(0, 5_000), HideDelay(4_999, 5_000), HideDelay(100, 1_000))) {
            val session = open()
            SettingsStore(session).save(AppSettings(hideDelay = range))
            assertEquals(range, SettingsStore(session).load().hideDelay)
            session.close()
        }
    }

    @Test
    fun savingTheHideDelayKeepsTheBulkCapAndProxyPolicy() {
        val session = open()
        SettingsStore(session).save(AppSettings(bulkCap = BulkCap.TWENTY, proxyPolicy = ProxyPolicy.WARN_AND_CONFIRM))
        SettingsStore(session).let { store -> store.load().copy(hideDelay = HideDelay(0, 500)).also(store::save) }

        val loaded = SettingsStore(session).load()
        assertEquals(BulkCap.TWENTY, loaded.bulkCap)
        assertEquals(ProxyPolicy.WARN_AND_CONFIRM, loaded.proxyPolicy)
        assertEquals(HideDelay(0, 500), loaded.hideDelay)
        session.close()
    }

    @Test
    fun aVersionTwoRecordReadsWithTheDefaultHideDelay() {
        val session = open()
        session.putVaultRecord("settings", recordV2(accounts = BulkCap.TWENTY.accounts, policyCode = ProxyPolicy.DEFAULT.code))

        val loaded = SettingsStore(session).load()
        assertEquals(BulkCap.TWENTY, loaded.bulkCap)
        assertEquals(HideDelay.DEFAULT, loaded.hideDelay)
        session.close()
    }

    @Test
    fun aHideDelayOutsideTheAcceptedRangeReadsAsTheDefaultAndKeepsTheRest() {
        for ((shortest, longest) in listOf(900 to 200, -1 to 500, 100 to 5_001)) {
            val session = open()
            session.putVaultRecord(
                "settings",
                recordV3(accounts = BulkCap.TWENTY.accounts, policyCode = ProxyPolicy.WARN_AND_CONFIRM.code, shortestMs = shortest, longestMs = longest),
            )

            val loaded = SettingsStore(session).load()
            assertEquals(BulkCap.TWENTY, loaded.bulkCap)
            assertEquals(ProxyPolicy.WARN_AND_CONFIRM, loaded.proxyPolicy)
            assertEquals(HideDelay.DEFAULT, loaded.hideDelay, "range $shortest to $longest")
            session.close()
        }
    }

    @Test
    fun anUnknownSettingsVersionReadsAsTheDefault() {
        val session = open()
        session.putVaultRecord("settings", record(version = 5, accounts = 1_000))

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

    @Test
    fun aSavedBitcoinPolicyIsReadBackAfterTheVaultReopens() {
        val first = open()
        SettingsStore(first).save(
            AppSettings(
                bitcoinInputPolicy = InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED,
                bitcoinSigningSource = SigningSourcePolicy.COMPANION_ONLY,
            ),
        )
        first.close()

        val reopened = open()
        val loaded = SettingsStore(reopened).load()
        assertEquals(InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED, loaded.bitcoinInputPolicy)
        assertEquals(SigningSourcePolicy.COMPANION_ONLY, loaded.bitcoinSigningSource)
        reopened.close()
    }

    @Test
    fun everyBitcoinPolicyRoundTrips() {
        val session = open()
        for (input in InputVerificationPolicy.entries) {
            for (source in SigningSourcePolicy.entries) {
                SettingsStore(session).save(AppSettings(bitcoinInputPolicy = input, bitcoinSigningSource = source))
                val loaded = SettingsStore(session).load()
                assertEquals(input, loaded.bitcoinInputPolicy)
                assertEquals(source, loaded.bitcoinSigningSource)
            }
        }
        session.close()
    }

    @Test
    fun aVersionThreeRecordReadsWithTheCoreBitcoinDefaults() {
        val session = open()
        session.putVaultRecord("settings", recordV3(accounts = 5, policyCode = 0, shortestMs = 250, longestMs = 4_000))

        val loaded = SettingsStore(session).load()
        assertEquals(HideDelay(250, 4_000), loaded.hideDelay)
        assertEquals(InputVerificationPolicy.STRICT, loaded.bitcoinInputPolicy)
        assertEquals(SigningSourcePolicy.ANY, loaded.bitcoinSigningSource)
        session.close()
    }

    @Test
    fun anUnknownBitcoinPolicyCodeReadsAsItsCoreDefaultAndTheRestIsKept() {
        val session = open()
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(4)
            out.writeInt(5)
            out.writeByte(0)
            out.writeInt(250)
            out.writeInt(4_000)
            out.writeByte(9)
            out.writeByte(SigningSourcePolicy.COMPANION_ONLY.wire)
        }
        session.putVaultRecord("settings", buffer.toByteArray())

        val loaded = SettingsStore(session).load()
        assertEquals(InputVerificationPolicy.STRICT, loaded.bitcoinInputPolicy)
        assertEquals(SigningSourcePolicy.COMPANION_ONLY, loaded.bitcoinSigningSource)
        assertEquals(HideDelay(250, 4_000), loaded.hideDelay)
        session.close()
    }
}
