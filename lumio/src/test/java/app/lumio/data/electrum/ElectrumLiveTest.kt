package app.lumio.data.electrum

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.btc.scriptOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, and skipped unless LUMIO_ELECTRUM_LIVE=1 is set, so CI never asks the public servers. It connects to each public server, which checks
 * its pinned or authority-checked certificate, and sends one batch of 40 scripthashes: the first 20 receive and the first 20 change addresses of
 * the published "abandon ... about" wallet. Every balance must come back.
 */
class ElectrumLiveTest {
    // The BIP39 seed of the published "abandon ... about" mnemonic, with no passphrase, as in BtcAddressAgreementTest.
    private val seed = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun everyPublicServerAnswersABatchOfFortyScripthashes() {
        assumeTrue("Set LUMIO_ELECTRUM_LIVE=1 to ask the public servers", System.getenv("LUMIO_ELECTRUM_LIVE") == "1")
        runBlocking {
            val account = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0)
            val addresses = (0 until 20).flatMap { listOf(account.receiveAddress(it), account.changeAddress(it)) }
            assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addresses.first())
            val hashes = addresses.map { scripthash(scriptOf(BtcNetwork.MAINNET, it)) }
            assertEquals(40, hashes.size)

            for (server in ElectrumServers.PUBLIC) {
                val connection = ElectrumConnection.open(server, ElectrumPool.CONNECT_TIMEOUT_MS, ElectrumPool.REQUEST_TIMEOUT_MS)
                try {
                    val replies = connection.scripthashBalances(hashes)
                    assertEquals(40, replies.size, server.host)
                    replies.forEach { assertTrue(it.isSuccess, "${server.host}: ${it.exceptionOrNull()}") }
                } finally {
                    connection.close()
                }
            }
        }
    }
}
