package app.lumio.pair

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignRequestEncoder
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.Payment
import app.airvault.chains.algorand.signer.AlgorandRequestParser
import app.airvault.chains.bitcoin.signer.BitcoinRequestParser
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaRequestParser
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestParser
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.partsOf
import app.lensly.core.algorand.AlgoTxnEncoder
import app.lensly.core.parse.ParserLimits
import app.lensly.core.solana.SolanaTransfer
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.RESERVED_KEYS_FROM
import app.lensly.core.ur.SolSignRequestEncoder
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    /** A Solana transfer and an Algorand payment, encoded as the Lumio builders encode them, so each chain's parser reads a real transaction. */
    private val solanaTransfer = SolanaTransfer.message(ByteArray(32) { 1 }, ByteArray(32) { 2 }, 1_000L, ByteArray(32) { 3 })
    private val algorandPayment = AlgoTxnEncoder.encodePayment(
        Payment(
            sender = ByteArray(32) { 1 },
            fee = 1_000UL,
            firstValid = 1UL,
            lastValid = 1_001UL,
            genesisId = "testnet-v1.0",
            genesisHash = ByteArray(32) { 4 },
            note = ByteArray(0),
            lease = null,
            group = null,
            rekeyTo = null,
            receiver = ByteArray(32) { 2 },
            amount = 1_000UL,
            closeTo = null,
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
                    signData = solanaTransfer,
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
                    signData = algorandPayment,
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

    /** The parser of the chain that reads each request type in [requests]. */
    private val parserOf: Map<String, RequestParser> = mapOf(
        "eth-sign-request" to EvmRequestParser,
        "sol-sign-request" to SolanaRequestParser,
        "algo-sign-request" to AlgorandRequestParser,
        BtcPsbt.UR_TYPE to BitcoinRequestParser,
    )

    /**
     * Every request type a Lumio builder writes is one a frozen Lensly reads, and the chain's parser parses it. Every key it writes is one that
     * Lensly reads, except a reserved key (from RESERVED_KEYS_FROM), which Lensly ignores and so needs no entry.
     */
    @Test
    fun everyRequestALumioBuilderWritesStaysInsideTheFrozenTable() {
        for ((name, parts) in requests) {
            val ur = urOf(parts)
            assertEquals(name, ur.type)
            val accepted = assertNotNull(FrozenLensly.REQUEST_KEYS[ur.type], name)
            val parser = assertNotNull(parserOf[ur.type], name)
            assertIs<ParseOutcome.Parsed>(parser.parse(ur.type, ur.cborBytes, ParserLimits()), name)
            val written = keysOf(ur).filter { it < RESERVED_KEYS_FROM }.toSet()
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
