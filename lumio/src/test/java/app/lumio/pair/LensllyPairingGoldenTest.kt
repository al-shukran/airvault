package app.lumio.pair

import app.lensly.app.export.LabelledPairingGolden
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.UrScanner
import app.lumio.data.net.Networks
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.DeterministicWallet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pairing Lensly exported for a labelled wallet, read by Lumio's PairImport. The frames are the ones WalletStoreBitcoinTest records
 * from Lensly, so this is the cross-app check: Lumio accepts what Lensly sends and reads the names, labels and groups of both Apps.
 */
class LensllyPairingGoldenTest {

    private fun imported(frames: List<String> = LabelledPairingGolden.FRAMES): PairingPayload = PairImport.of(scanned(frames))

    private fun scanned(frames: List<String>): UR {
        val scanner = UrScanner()
        frames.forEach { scanner.receive(it) }
        return requireNotNull(scanner.result())
    }

    @Test
    fun lumioAcceptsTheLenslyPairingAndReadsTheEthereumNames() {
        val payload = imported()

        assertEquals("Savings", payload.name)
        assertEquals(
            listOf(Triple("Cold", "vault", "Long term"), Triple("Savings 1", null, null)),
            PairImport.accountsOf(payload, Networks.EIP155).map { Triple(it.name, it.label, it.group) },
        )
    }

    @Test
    fun lumioReadsTheBitcoinNamesLabelsAndGroupsOfEachKeyLensliesSent() {
        val payload = imported()
        val named = PairImport.accountsOf(payload, Networks.BIP122)
        val accounts = PairImport.bitcoinAccountsOf(payload)

        // The pairing carries the two Bitcoin accounts the owner stores, and no other Script Type.
        assertEquals(2, named.size)
        assertEquals(named.size, accounts.size)
        val cold = keyOf(accounts, ScriptType.NATIVE_SEGWIT, index = 0)
        assertEquals(Triple("Cold BTC", "vault", "Long term"), named[cold].let { Triple(it.name, it.label, it.group) })
        // An account the owner never labelled carries its Auto-Name.
        val unnamed = keyOf(accounts, ScriptType.NATIVE_SEGWIT, index = 1)
        assertEquals(Triple("Savings 1", null, null), named[unnamed].let { Triple(it.name, it.label, it.group) })
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
