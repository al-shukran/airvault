package app.lensly.ui.flow

import android.content.Context
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.sign.ScannedRequest
import app.lensly.app.sign.WalletMatch
import app.lensly.app.sign.routeScan
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcPsbt
import app.lensly.core.btc.BtcUnsignedPsbt
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import fr.acinq.bitcoin.psbt.Psbt
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
 * Which wallet a scanned Bitcoin request is routed to, read through the sign flow's ports as the app reads the vault. A vault that stores
 * only a Native Segwit account at index 0 routes a request from that account, and no request from another Script Type of that index: the
 * pairing carries the stored accounts alone.
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

            val seed = Mnemonic.toSeed(words)
            for (type in ScriptType.entries) {
                val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, type, 0)
                val coin = BtcCoin(BtcFixtures.funding(account, 0, 0, 100_000L), 0, 0, 0)
                val built = BtcUnsignedPsbt.build(
                    account.watchOnly(),
                    listOf(coin),
                    BtcFixtures.paying(account, paid = 30_000L, change = 60_000L),
                )
                // The request as the sign flow reads it: the serialized PSBT of the scan, parsed again.
                val request = ScannedRequest.Bitcoin(BtcPsbt.parse(Psbt.write(built).toByteArray()))
                val expected = if (type == ScriptType.NATIVE_SEGWIT) WalletMatch.One(wallet) else WalletMatch.None
                assertEquals(expected, routeScan(request, wallets), type.name)
            }
        } finally {
            session.close()
        }
    }
}
