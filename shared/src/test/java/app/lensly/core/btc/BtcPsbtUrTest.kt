package app.lensly.core.btc

import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.NOT_A_PSBT
import app.lensly.core.parse.InProcessParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

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
        assertContentEquals(Psbt.write(psbt).toByteArray(), BtcPsbt.requestPsbt(assertIs<ScanReply.App>(reply).request.body))
    }

    /** The same frames under the registry's `psbt` name scan to the same PSBT, and the request keeps that name. */
    @Test
    fun aPsbtUnderTheRegistryNameScansAsARequestUnderThatName() {
        val psbt = largePsbt()
        val count = BtcPsbt.encodeUrParts(psbt).size
        val parts = framesOf(UR(BtcPsbt.PSBT_UR_TYPE, BtcPsbt.cborOf(psbt)), DEFAULT_MAX_FRAGMENT).take(count).toList()
        val request = assertIs<ScanReply.App>(InProcessParser.scan(parts)).request
        assertEquals(BtcPsbt.PSBT_UR_TYPE, BtcPsbt.requestUrType(request.body))
        assertContentEquals(Psbt.write(psbt).toByteArray(), BtcPsbt.requestPsbt(request.body))
    }

    @Test
    fun aPsbtUrThatDoesNotParseIsRefusedByTheSandbox() {
        val ur = UREncoder.encode(CryptoPSBT(byteArrayOf(1, 2, 3)).toUR())
        assertEquals(ScanReply.Refused(NOT_A_PSBT), InProcessParser.scan(listOf(ur)))
    }
}
