package app.lensly.core.btc

import app.lensly.core.parse.InProcessParser
import app.lensly.core.parse.NOT_A_PSBT
import app.lensly.core.parse.ScanReply
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.psbt.Psbt
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BtcPsbtUrTest {
    private val seed = BitcoinVectors.seed()
    private val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)

    /** Three inputs of the account, so the PSBT is larger than one QR frame. */
    private fun largePsbt(): Psbt {
        val spends = (0..2).map { i -> BtcFixtures.Spend(BtcFixtures.funding(account, 0, i, 100_000L, salt = i + 1), account, 0, i) }
        return BtcFixtures.psbt(spends, BtcFixtures.paying(account, paid = 90_000L, change = 150_000L))
    }

    @Test
    fun aLargePsbtIsAnAnimatedCodeThatScansBackToTheSamePsbt() {
        val psbt = largePsbt()
        val parts = BtcPsbt.encodeUrParts(psbt)
        assertTrue(parts.size > 1, "expected several frames, got ${parts.size}")
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        val ur = assertNotNull(scanner.result())
        assertEquals(BtcPsbt.UR_TYPE, ur.type)
        assertContentEquals(Psbt.write(psbt).toByteArray(), Psbt.write(BtcPsbt.fromUr(ur)).toByteArray())
    }

    @Test
    fun theSandboxReturnsTheSerializedPsbtOfAScannedPsbtUr() {
        val psbt = largePsbt()
        val reply = InProcessParser.scan(BtcPsbt.encodeUrParts(psbt))
        assertContentEquals(Psbt.write(psbt).toByteArray(), assertIs<ScanReply.Bitcoin>(reply).psbt)
    }

    @Test
    fun aPsbtUrThatDoesNotParseIsRefusedByTheSandbox() {
        val ur = UREncoder.encode(CryptoPSBT(byteArrayOf(1, 2, 3)).toUR())
        assertEquals(ScanReply.Refused(NOT_A_PSBT), InProcessParser.scan(listOf(ur)))
    }
}
