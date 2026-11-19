package app.lensly.ui.flow

import android.content.Context
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolanaRequestParser
import app.airvault.core.model.Approval
import app.airvault.core.model.checkLabels
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ParseOutcome
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.core.parse.ParserLimits
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.flow.SolanaRequestFixtures.REQUEST_ID
import app.lensly.ui.flow.SolanaRequestFixtures.legacyTransfer
import app.lensly.ui.flow.SolanaRequestFixtures.signatureOf
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app's Review Record writer, and the Solana branch of the one sign flow's ports, against a real vault. A store that fails in a way the
 * writer does not name still becomes [ReviewNotSaved], so no unexpected exception reaches the sign flow.
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
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(
                context,
                holderOf(session),
                checkSolana = { _, _, _, _ -> throw IllegalStateException("the check failed") },
            )
            val request = solanaRequest(legacyTransfer(ByteArray(32) { 1 }), fingerprintOf(ports))

            val prepared = runBlocking { ports.appPrepare(id, request) }

            assertEquals(
                "This Solana request could not be reviewed. Nothing was signed.",
                assertIs<AppPrepared.Refused>(prepared).reason,
            )
        } finally {
            session.close()
        }
    }

    /** A Solana request that names no requestId is refused, since its response must echo one. */
    @Test
    fun aSolanaRequestWithoutARequestIdIsRefused() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(context, holderOf(session))
            val request = solanaRequest(legacyTransfer(SolanaAccount.publicKey(seed(), 0)), fingerprintOf(ports), requestId = null)

            val prepared = runBlocking { ports.appPrepare(id, request) }

            assertEquals("Request has no ID", assertIs<AppPrepared.Refused>(prepared).reason)
        } finally {
            session.close()
        }
    }

    /** A Solana request that names another wallet's fingerprint is refused before any key is read. */
    @Test
    fun aSolanaRequestForAnotherWalletIsRefused() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(context, holderOf(session))
            val request = solanaRequest(legacyTransfer(ByteArray(32) { 1 }), fingerprintOf(ports) xor 1)

            val prepared = runBlocking { ports.appPrepare(id, request) }

            assertEquals("This request is for a different wallet", assertIs<AppPrepared.Refused>(prepared).reason)
        } finally {
            session.close()
        }
    }

    /**
     * The sol-signature of the reviewed account verifies with the key the wallet stores for that account, and with no other. The app checks the
     * stored key before it signs, so the key that signs is the one the wallet holds.
     */
    @Test
    fun aSolanaSignatureVerifiesWithTheStoredKeyOfTheReviewedAccount() {
        val (session, id) = sessionWithWallet()
        try {
            val store = WalletStore(session)
            store.addSolanaAccounts(id, 2, BulkCap.DEFAULT.accounts)
            val ports = VaultSignPorts(context, holderOf(session))
            val message = legacyTransfer(SolanaAccount.publicKey(seed(), 1))
            val ready = reviewed(ports, id, solanaRequest(message, fingerprintOf(ports), index = 1L))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))

            val signature = signatureOf(runBlocking { ports.appSign(ready, approval) })

            assertTrue(Ed25519Signer.verify(store.solanaKeys(id)[1], message, signature))
            assertFalse(Ed25519Signer.verify(store.solanaKeys(id)[0], message, signature))
        } finally {
            session.close()
        }
    }

    /** A reviewed account that the wallet holds no key for is refused, and nothing is signed. */
    @Test
    fun aSolanaSignatureForAnAccountTheWalletDoesNotHaveIsRefused() {
        val (session, id) = sessionWithWallet()
        try {
            WalletStore(session).addSolanaAccounts(id, 1, BulkCap.DEFAULT.accounts)
            val ports = VaultSignPorts(context, holderOf(session))
            val ready = reviewed(ports, id, solanaRequest(legacyTransfer(SolanaAccount.publicKey(seed(), 0)), fingerprintOf(ports)))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))

            assertFailsWith<IllegalArgumentException> {
                runBlocking { ports.appSign(ready.copy(accountIndex = 5), approval) }
            }
        } finally {
            session.close()
        }
    }

    /** A vault session holding one wallet of [words], and that wallet's id. The caller closes the session. */
    private fun sessionWithWallet(): Pair<VaultSession, String> {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        val id = WalletStore(session).create(name = "MAIN", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
        return session to id
    }

    /** A holder that reads and writes through [session], as the open vault does. */
    private fun holderOf(session: VaultSession): VaultHolder {
        val vault = Vault(
            VaultLayout(File(tmp.root, "vault")),
            MemoryKeystore(),
            FakeClock(),
            KdfParams(memoryKiB = 8 * 1024, iterations = 1),
        )
        val holder = VaultHolder(vault)
        holder.adopt(session, holder.unlockEpoch())
        return holder
    }

    private fun seed(): ByteArray = Mnemonic.of(words).use { it.deriveSeed("") }

    /** The master fingerprint of the one wallet [ports] shows. */
    private fun fingerprintOf(ports: VaultSignPorts): Int = checkNotNull(runBlocking { ports.wallets() }.single().masterFingerprint)

    /** The request a wallet sends for [signData] on account [index] of the wallet with [fingerprint], parsed as the Parser Sandbox parses it. */
    private fun solanaRequest(signData: ByteArray, fingerprint: Int, index: Long = 0L, requestId: ByteArray? = REQUEST_ID): AppRequest {
        val path = listOf(44L to true, 501L to true, index to true)
        val cbor = SolanaRequestFixtures.requestCbor(signData, fingerprint, path, requestId)
        return assertIs<ParseOutcome.Parsed>(SolanaRequestParser.parse(SolSignRequest.UR_TYPE, cbor, ParserLimits())).request
    }

    /** The review of [request] against wallet [id], which must pass every check. */
    private fun reviewed(ports: VaultSignPorts, id: String, request: AppRequest): AppPrepared.Ready =
        assertIs<AppPrepared.Ready>(runBlocking { ports.appPrepare(id, request) })

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
