package app.lensly.ui.flow

import android.content.Context
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The app's Review Record writer. A store that fails in a way the writer does not name still becomes [ReviewNotSaved], so no
 * unexpected exception reaches the sign flow.
 */
@RunWith(RobolectricTestRunner::class)
class VaultSignPortsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val words = List(23) { "abandon" } + "art"

    @Test
    fun aStoreThatThrowsAnUnexpectedExceptionIsReportedAsReviewNotSaved() {
        val layout = VaultLayout(tmp.root)
        val session = VaultSession(layout, MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        try {
            val wallet = WalletStore(session).create(
                name = "MAIN",
                deviceGenerated = false,
                words = words,
                passphrase = null,
                nowMs = 1L,
            )
            val vault = Vault(
                VaultLayout(File(tmp.root, "vault")),
                MemoryKeystore(),
                FakeClock(),
                KdfParams(memoryKiB = 8 * 1024, iterations = 1),
            )
            val holder = VaultHolder(vault)
            holder.adopt(session, holder.unlockEpoch())
            val ports = VaultSignPorts(context, holder) { _, _, _ ->
                throw GeneralSecurityException("the store failed")
            }

            assertFailsWith<ReviewNotSaved> {
                runBlocking {
                    ports.record(
                        ReviewEntry(wallet, ReviewOutcome.REFUSED, request(), ready = null, refusal = "Refused"),
                    )
                }
            }
        } finally {
            session.close()
        }
    }

    /** A Solana check that throws is reported as a refusal, not as a crash, and nothing is signed. */
    @Test
    fun aSolanaCheckThatThrowsIsReportedAsARefusal() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        try {
            WalletStore(session).create(name = "MAIN", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
            val vault = Vault(
                VaultLayout(File(tmp.root, "vault")),
                MemoryKeystore(),
                FakeClock(),
                KdfParams(memoryKiB = 8 * 1024, iterations = 1),
            )
            val holder = VaultHolder(vault)
            holder.adopt(session, holder.unlockEpoch())
            val ports = VaultSignPorts(context, holder, checkSolana = { _, _, _, _ -> throw IllegalStateException("the check failed") })
            val wallet = runBlocking { ports.wallets() }.single()
            val request = SolSignRequest(
                requestId = null,
                signData = byteArrayOf(1, 2, 3),
                derivationPath = emptyList(),
                sourceFingerprint = wallet.masterFingerprint,
                address = null,
                origin = null,
                signType = SolSignType.TRANSACTION,
            )

            val prepared = runBlocking { ports.solanaPrepare(wallet.id, request) }

            assertEquals(
                "This Solana request could not be reviewed. Nothing was signed.",
                assertIs<SolanaPrepared.Refused>(prepared).reason,
            )
        } finally {
            session.close()
        }
    }

    private fun request() = EthSignRequest(
        requestId = ByteArray(16) { 7 },
        signData = byteArrayOf(1, 2, 3),
        dataType = DataType.PERSONAL_MESSAGE,
        chainId = null,
        derivationPath = emptyList(),
        sourceFingerprint = null,
        address = null,
        origin = null,
    )
}
