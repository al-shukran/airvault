package app.lumio.wallets

import android.os.Looper
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.data.asset.AssetId
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.network.NetworkMode
import app.lumio.pair.PairImport
import app.lumio.pair.PairedWallets
import com.sparrowwallet.hummingbird.UR
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The wallet board holds each account's rows before its sheet opens. Opening the board reads the account's tokens, its prices and its newest activity
 * from the vault's caches and store, so the Account screen has its rows on the first frame, and no network read is needed for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class WalletsBoardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mode = NetworkMode.TESTNET

    @Test
    fun theBoardHoldsAnEthereumAccountsRowsBeforeItsSheetOpens() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "board.db"))
        val vault = LumioVault(session, database)
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        runBlocking { vault.txs.put(listOf(transfer(wallet.evmAddresses.single()))) }

        // The account has no balance in the caches, so no asset is priced and no price source is asked.
        val board = WalletsBoard(RuntimeEnvironment.getApplication() as LumioApp, vault) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)
        val account = await { board.view?.accountsOf(wallet.id)?.singleOrNull { it.chain == AccountChain.ETHEREUM } }
        val reads = board.readsOf(account, mode)

        val activity = await { reads.activity }
        assertEquals(listOf(TX_ID), activity.map { it.tx.id })
        assertEquals(emptyList<Any>(), reads.tokens)

        board.close()
        database.close()
        session.close()
    }

    /** A paired wallet with one Ethereum account, as the pairing carries it. */
    private fun pairing(): PairingPayload {
        val payload = PairingPayload(
            fingerprint = 0x5436D724L,
            name = "Demo",
            autoNameBase = "Demo",
            apps = listOf(
                PairingApp(
                    app = PairImport.ETHEREUM,
                    keys = listOf(PairingKey.ethereumAddress(0, ADDRESS)),
                    accounts = listOf(PairingAccount(key = 0, name = "Main", label = null, group = null)),
                ),
            ),
        )
        return PairImport.of(UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload, null)))
    }

    private fun transfer(address: String) = LumioTx(
        id = TX_ID,
        network = NetworkId(Networks.evm(10143), testnet = true),
        account = address,
        hash = "0x01",
        timestampMs = 1_000L,
        asset = TxAsset(contract = null, symbol = "MON", name = "Monad", decimals = 18),
        amount = BigInteger.ONE,
        direction = TxDirection.IN,
        fee = null,
        counterparty = null,
        status = TxStatus.CONFIRMED,
        confirmations = 1L,
        inputData = null,
        memo = null,
    )

    /** Runs the main looper until [read] has a value, since the board shows its reads on the main thread and reads the vault off it. */
    private fun <T : Any> await(read: () -> T?): T {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            read()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "the board did not hold the account in time" }
            Thread.sleep(5)
        }
    }

    private companion object {
        val ADDRESS = "0x" + "a1".repeat(20)
        const val TX_ID = "EVM:10143:tx1"
        const val TIMEOUT_MS = 10_000L
    }
}
