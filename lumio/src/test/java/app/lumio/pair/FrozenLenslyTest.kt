package app.lumio.pair

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignRequestEncoder
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.partsOf
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequestEncoder
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class FrozenLenslyTest {
    private val psbt = Psbt(
        Transaction(
            version = 2,
            txIn = emptyList(),
            txOut = listOf(TxOut(Satoshi(1_000L), ByteVector.empty)),
            lockTime = 0L,
        ),
    )

    /**
     * The frames of each request a Lumio builder writes, with every optional field set. The builders hand these encoders their requests, and a
     * key is written only when its field is set, so a request with every field set writes every key a builder can write.
     */
    private val requests: Map<String, List<String>> = mapOf(
        "eth-sign-request" to partsOf(
            EthSignRequestEncoder.encode(
                EthSignRequest(
                    requestId = ByteArray(16) { 1 },
                    signData = ByteArray(32) { 2 },
                    dataType = DataType.TRANSACTION,
                    chainId = 1L,
                    derivationPath = listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(0, false)),
                    sourceFingerprint = 0x01020304,
                    address = ByteArray(20) { 3 },
                    origin = "lumio",
                ),
            ),
        ),
        "sol-sign-request" to partsOf(
            SolSignRequestEncoder.encode(
                SolSignRequest(
                    requestId = ByteArray(16) { 1 },
                    signData = ByteArray(32) { 2 },
                    derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
                    sourceFingerprint = 0x01020304,
                    address = ByteArray(32) { 3 },
                    origin = "lumio",
                    signType = SolSignType.TRANSACTION,
                ),
            ),
        ),
        "algo-sign-request" to partsOf(
            AlgoSignRequestEncoder.encode(
                AlgoSignRequest(
                    requestId = ByteArray(16) { 1 },
                    signData = ByteArray(32) { 2 },
                    derivationPath = listOf(PathStep(44, true), PathStep(283, true), PathStep(0, true), PathStep(0, false), PathStep(0, false)),
                    sourceFingerprint = 0x01020304,
                    address = "AAAA",
                    origin = "Lumio",
                    signType = AlgoSignType.TRANSACTION,
                ),
            ),
        ),
        BtcPsbt.UR_TYPE to BtcPsbt.encodeUrParts(psbt),
    )

    /** Every request type a Lumio builder writes is one a frozen Lensly reads, and every key it writes is one that Lensly reads. */
    @Test
    fun everyRequestALumioBuilderWritesStaysInsideTheFrozenTable() {
        for ((name, parts) in requests) {
            val ur = urOf(parts)
            assertEquals(name, ur.type)
            val accepted = assertNotNull(FrozenLensly.REQUEST_KEYS[ur.type], name)
            val written = keysOf(ur)
            assertTrue(accepted.containsAll(written), "$name writes keys $written, and a frozen Lensly reads $accepted")
        }
    }

    /** The table is keyed on pairing version 7. A new pairing version needs a table of its own, so this test fails until one is written. */
    @Test
    fun theTableIsKeyedOnPairingVersionSeven() {
        assertEquals(7L, FrozenLensly.PAIRING_VERSION)
        assertEquals(FrozenLensly.PAIRING_VERSION, PairingCodec.VERSION)
    }

    private fun urOf(parts: List<String>): UR {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return assertNotNull(scanner.result())
    }

    /** The map keys of a request payload. A PSBT is not a map, so it names none. */
    private fun keysOf(ur: UR): Set<Long> =
        (Cbor.decode(ur.cborBytes) as? Cbor.Dict)?.entries?.map { (key, _) -> (key as Cbor.Uint).value }?.toSet().orEmpty()
}
