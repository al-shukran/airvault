package app.lensly.ui.flow

import android.content.Context
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.sign.ScannedRequest
import app.lensly.app.sign.WalletMatch
import app.lensly.app.sign.routeScan
import app.lensly.core.ur.PathStep
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Which wallet a scanned Bitcoin request is routed to, read through the app's vault. A vault that stores only a Native Segwit account at index 0
 * routes a request from that account, and no request from another Script Type of that index: the pairing carries the stored accounts alone.
 *
 * The request is the header the parser makes for an input of account 0 of each Script Type. The parser is not run, since Robolectric cannot decode
 * the `crypto-psbt` CBOR (hummingbird needs a newer cbor than the Robolectric classpath has).
 */
@RunWith(RobolectricTestRunner::class)
class BitcoinRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val words = (
        "supply excess unit online visa snow include grief sketch " +
            "grace rural near leaf finish jewel embark elite make venture enroll vacuum worth over pioneer"
        ).split(" ")

    @Test
    fun aRequestFromTheStoredScriptTypeRoutesToTheWalletAndAnotherScriptTypeDoesNot() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        try {
            val store = WalletStore(session)
            val wallet = store.create(name = "Lumio", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
            store.addBitcoinAccounts(wallet, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, BulkCap.DEFAULT.accounts)
            val vault = Vault(
                VaultLayout(File(tmp.root, "vault")),
                MemoryKeystore(),
                FakeClock(),
                KdfParams(memoryKiB = 8 * 1024, iterations = 1),
            )
            val holder = VaultHolder(vault)
            holder.adopt(session, holder.unlockEpoch())
            val wallets = runBlocking { VaultSignPorts(context, holder).wallets() }
            val fingerprint = checkNotNull(wallets.single().masterFingerprint)

            for (type in ScriptType.entries) {
                val path = listOf(PathStep(type.purpose, hardened = true), PathStep(1, hardened = true), PathStep(0, hardened = true))
                val request = ScannedRequest.App(
                    RequestHeader(
                        app = bitcoinManifest.id,
                        chain = bitcoinManifest.networks.single { it.testnet }.chainId,
                        kind = RequestKind.TRANSACTION,
                        signers = listOf(SignerRef(fingerprint, path)),
                        requestId = null,
                    ),
                )
                val expected = if (type == ScriptType.NATIVE_SEGWIT) WalletMatch.One(wallet) else WalletMatch.None
                assertEquals(expected, routeScan(request, wallets), type.name)
            }
        } finally {
            session.close()
        }
    }
}
