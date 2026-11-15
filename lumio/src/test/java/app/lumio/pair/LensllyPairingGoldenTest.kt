package app.lumio.pair

import app.lensly.app.export.LabelledPairingGolden
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.UrScanner
import fr.acinq.bitcoin.DeterministicWallet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pairing Lensly exported for a labelled wallet, read by Lumio's PairImport. The frames are the ones WalletStoreBitcoinTest records
 * from Lensly, so this is the cross-app check: Lumio accepts what Lensly sends and reads the names, labels and groups of both Apps.
 */
class LensllyPairingGoldenTest {

    private fun imported(): PairingPayload {
        val scanner = UrScanner()
        LabelledPairingGolden.FRAMES.forEach { scanner.receive(it) }
        return PairImport.of(requireNotNull(scanner.result()))
    }

    @Test
    fun lumioAcceptsTheLenslyPairingAndReadsTheEthereumNames() {
        val payload = imported()

        assertEquals("Savings", payload.name)
        assertEquals(
            listOf(
                PairingAccount(key = 0, child = 0, name = "Cold", label = "vault", group = "Long term"),
                PairingAccount(key = 0, child = 1, name = "Savings 1", label = null, group = null),
            ),
            payload.apps.single { it.app == PairImport.ETHEREUM }.accounts,
        )
    }

    @Test
    fun lumioReadsTheBitcoinNamesLabelsAndGroupsOfEachKeyLensliesSent() {
        val payload = imported()
        val bitcoin = payload.apps.single { it.app == PairImport.BITCOIN }
        val accounts = PairImport.bitcoinAccountsOf(payload)

        assertEquals(8, bitcoin.keys.size)
        assertEquals(bitcoin.keys.size, bitcoin.accounts.size)
        val named = keyOf(accounts, ScriptType.NATIVE_SEGWIT, index = 0)
        assertEquals(
            PairingAccount(key = named, child = null, name = "Cold BTC", label = "vault", group = "Long term"),
            bitcoin.accounts.single { it.key == named },
        )
        // An account the owner never labelled carries its Auto-Name, and the Script Type it was not made in is still paired.
        val unnamed = keyOf(accounts, ScriptType.TAPROOT, index = 0)
        assertEquals(
            PairingAccount(key = unnamed, child = null, name = "Savings 0", label = null, group = null),
            bitcoin.accounts.single { it.key == unnamed },
        )
    }

    /** The place, among the Bitcoin accounts of [payload], of the mainnet account on [scriptType] at account [index]. */
    private fun keyOf(accounts: List<app.lensly.core.btc.BtcWatchOnlyAccount>, scriptType: ScriptType, index: Int): Int {
        val key = accounts.indexOfFirst {
            it.scriptType == scriptType && it.network == BtcNetwork.MAINNET && it.path.path[2] == DeterministicWallet.hardened(index.toLong())
        }
        assertEquals("the pairing holds $scriptType at $index on mainnet", true, key >= 0)
        return key
    }
}
