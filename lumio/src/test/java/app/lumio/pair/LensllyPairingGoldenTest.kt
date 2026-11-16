package app.lumio.pair

import app.lensly.app.export.LabelledPairingGolden
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.DeterministicWallet
import kotlin.test.assertFailsWith
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

    /**
     * The code of [ur] with its version set to [version]. A version-4 envelope is a map of three entries whose first entry is `1: 4`, so the
     * version is the third byte, after the map header `A3` and the key `01`. Only that byte changes, so the body is the same.
     */
    private fun withVersion(ur: UR, version: Int): UR {
        val bytes = ur.cborBytes.copyOf()
        require(bytes[0] == 0xA3.toByte() && bytes[1] == 0x01.toByte() && bytes[2] == 0x04.toByte()) { "Not a version-4 envelope." }
        bytes[2] = version.toByte()
        return UR(ur.type, bytes)
    }

    @Test
    fun lumioAcceptsTheLenslyPairingAndReadsTheEthereumNames() {
        val payload = imported()

        assertEquals("Savings", payload.name)
        assertEquals(
            listOf(
                PairingAccount(key = 0, child = null, name = "Cold", label = "vault", group = "Long term"),
                PairingAccount(key = 1, child = null, name = "Savings 1", label = null, group = null),
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

    @Test
    fun lumioRefusesTheVersionOneCodeWithTheUpdateMessage() {
        val reason = assertFailsWith<PairingRefusal> { imported(LabelledPairingGolden.V1_FRAMES) }.message

        assertEquals("This pairing uses format 1, and this app reads format 5. Update the app, then pair again.", reason)
    }

    @Test
    fun lumioStillAcceptsTheVersionFourCodeOfAnEarlierLensly() {
        val payload = imported(LabelledPairingGolden.V4_FRAMES)

        assertEquals("Savings", payload.name)
        assertEquals(
            listOf(
                PairingAccount(key = 0, child = null, name = "Cold", label = "vault", group = "Long term"),
                PairingAccount(key = 1, child = null, name = "Savings 1", label = null, group = null),
            ),
            payload.apps.single { it.app == PairImport.ETHEREUM }.accounts,
        )
    }

    @Test
    fun lumioStillAcceptsTheVersionThreeCodeWithTheSameFields() {
        val payload = PairImport.of(withVersion(scanned(LabelledPairingGolden.V4_FRAMES), version = 3))

        assertEquals("Savings", payload.name)
        assertEquals(2, payload.apps.single { it.app == PairImport.ETHEREUM }.accounts.size)
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
