package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairFixtures
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

/**
 * The key names a Bitcoin account in the scan cache, the scan queue and the screen routes. Two accounts of one wallet on one network and script
 * type must never share it, or they share one scan, one balance and one queued job.
 */
class BtcAccountRefKeyTest {
    private fun ref(walletId: String, accountIndex: Int, network: BtcNetwork = BtcNetwork.TESTNET4, scriptType: ScriptType = ScriptType.NATIVE_SEGWIT) =
        BtcAccountRef(walletId, BtcAccount.fromSeed(PairFixtures.seed, network, scriptType, accountIndex).watchOnly())

    @Test
    fun twoAccountsOfOneWalletOnOneNetworkAndScriptTypeHaveDifferentKeys() {
        val first = ref("wallet-1", accountIndex = 0)
        val second = ref("wallet-1", accountIndex = 1)

        assertNotEquals(first.key, second.key)
    }

    @Test
    fun aKeyIsStableForTheSameAccount() {
        assertEquals(ref("wallet-1", accountIndex = 1).key, ref("wallet-1", accountIndex = 1).key)
    }

    @Test
    fun theSameAccountNumberInTwoWalletsHasDifferentKeys() {
        assertNotEquals(ref("wallet-1", accountIndex = 0).key, ref("wallet-2", accountIndex = 0).key)
    }

    @Test
    fun theScriptTypeAndTheNetworkStillSeparateKeys() {
        val native = ref("wallet-1", accountIndex = 0)

        assertNotEquals(native.key, ref("wallet-1", accountIndex = 0, scriptType = ScriptType.TAPROOT).key)
        assertNotEquals(native.key, ref("wallet-1", accountIndex = 0, network = BtcNetwork.MAINNET).key)
    }
}
