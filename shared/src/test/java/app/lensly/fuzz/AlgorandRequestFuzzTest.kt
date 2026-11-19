package app.lensly.fuzz

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.signer.AlgorandRequestParser
import app.airvault.core.signer.ParseOutcome
import app.lensly.core.algorand.AlgoTxnVectors
import app.lensly.core.parse.ParserLimits
import app.lensly.core.parse.algorandRequestCbor
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Seeded fuzzing of the Algorand request parser (spec 9.1). Random, truncated and mutated input must give an outcome and never throw,
 * and a parsed request never exceeds the limits. The seed is fixed, so CI runs the same inputs every time.
 */
class AlgorandRequestFuzzTest {

    private val random = Random(SEED)
    private val txn = AlgoTxnVectors.valid().single { it.name == "pay_basic" }.raw

    @Test
    fun randomBytesAreRefusedWithoutThrowing() {
        repeat(RUNS) {
            assertIs<ParseOutcome.Refused>(parse(random.nextBytes(random.nextInt(MAX_RANDOM_BYTES))))
        }
    }

    @Test
    fun everyTruncationOfARequestIsRefused() {
        val request = algorandRequestCbor(txn, signType = 1L)
        for (end in 0 until request.size) {
            assertIs<ParseOutcome.Refused>(parse(request.copyOf(end)))
        }
    }

    @Test
    fun everyTruncatedTransactionIsRefusedInsideAValidRequest() {
        for (end in 0 until txn.size) {
            assertIs<ParseOutcome.Refused>(parse(algorandRequestCbor(txn.copyOf(end), signType = 1L)))
        }
    }

    @Test
    fun randomTransactionBytesAreRefusedInsideAValidRequest() {
        repeat(RUNS) {
            val signData = random.nextBytes(random.nextInt(MAX_RANDOM_BYTES))
            assertIs<ParseOutcome.Refused>(parse(algorandRequestCbor(signData, signType = 1L)))
        }
    }

    @Test
    fun mutatedRequestsNeverThrowAndStayInsideTheLimits() {
        val base = algorandRequestCbor(txn, signType = 1L)
        repeat(RUNS) {
            val mutated = base.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
            when (val outcome = parse(mutated)) {
                is ParseOutcome.Parsed -> assertTrue(outcome.request.body.size <= AlgoSignRequest.MAX_PAYLOAD_BYTES)
                is ParseOutcome.Refused -> Unit
            }
        }
    }

    private fun parse(cbor: ByteArray): ParseOutcome = AlgorandRequestParser.parse(AlgoSignRequest.UR_TYPE, cbor, ParserLimits())

    private companion object {
        const val SEED = 0x5eed_2c95L
        const val RUNS = 2_000
        const val MAX_RANDOM_BYTES = 512
    }
}
