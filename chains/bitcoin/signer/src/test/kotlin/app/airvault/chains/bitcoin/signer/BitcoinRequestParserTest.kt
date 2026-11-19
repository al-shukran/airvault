package app.airvault.chains.bitcoin.signer

import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainRegistry
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestKind
import app.lensly.core.parse.ParserLimits
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BitcoinRequestParserTest {
    private val bytes = Psbt.write(
        Psbt(
            Transaction(
                version = 2,
                txIn = emptyList(),
                txOut = listOf(TxOut(Satoshi(1_000L), ByteVector.empty)),
                lockTime = 0L,
            ),
        ),
    ).toByteArray()
    private val ur = CryptoPSBT(bytes).toUR()

    private fun parse(
        urType: String = ur.type,
        cbor: ByteArray = ur.cborBytes,
        limits: ParserLimits = ParserLimits(),
    ) = BitcoinRequestParser.parse(urType, cbor, limits)

    @Test
    fun aPsbtParsesToARequestWhoseBodyIsThePsbt() {
        val request = assertIs<ParseOutcome.Parsed>(parse()).request
        assertEquals(bitcoinManifest.id, request.header.app)
        assertEquals(ChainId("bip122:000000000019d6689c085ae165831e93"), request.header.chain)
        assertEquals(RequestKind.TRANSACTION, request.header.kind)
        assertContentEquals(bytes, request.body)
    }

    @Test
    fun aUrOfAnotherTypeIsRefused() {
        assertIs<ParseOutcome.Refused>(parse(urType = "algo-sign-request"))
    }

    @Test
    fun aPayloadThatIsNotAPsbtIsRefusedWithTheReason() {
        assertEquals(ParseOutcome.Refused(NOT_A_PSBT), parse(cbor = byteArrayOf(1, 2, 3)))
    }

    @Test
    fun aPsbtLargerThanTheLimitIsRefused() {
        assertIs<ParseOutcome.Refused>(parse(limits = ParserLimits(maxSignDataBytes = bytes.size - 1)))
    }

    @Test
    fun theRegistryRoutesCryptoPsbtToTheBitcoinApp() {
        assertEquals(BitcoinSigner, ChainRegistry(listOf(BitcoinSigner)).byRequestType(BtcPsbt.UR_TYPE))
    }
}
