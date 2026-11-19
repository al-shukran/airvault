package app.lensly.core.parse

import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import app.airvault.chains.algorand.signer.AlgoTxnException
import app.airvault.chains.evm.signer.TypedDataParser
import app.lensly.core.btc.BtcPsbt
import app.lensly.core.solana.SolanaMessage
import app.lensly.core.solana.SolanaOffchainMessage
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

private const val SEED = 0x5EED
private const val ITERATIONS = 10_000
private const val BUDGET_NANOS = 5_000_000_000L

/** The BIP174 magic, `psbt` followed by 0xff. */
private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

/**
 * Property checks for the parsers that read bytes or text from outside the app: the PSBT, Solana messages and off-chain
 * messages, Algorand transactions, and EIP-712 typed data. Each parser gets [ITERATIONS] inputs from a fixed seed, so a
 * failure reproduces from the input it names.
 *
 * A parser may throw only the refusal it declares: [IllegalArgumentException] for the PSBT, [AlgoTxnException] for Algorand.
 * The Solana and typed-data parsers return their refusal as a value and never throw, so any exception fails their run. Each
 * run must also finish within [BUDGET_NANOS].
 *
 * Inputs mix random bytes with the start of each format, so the runs reach past the first check of each parser.
 */
class ParserPropertyTest {

    @Test
    fun thePsbtParserRefusesOnlyWithIllegalArgument() =
        propertyRun("BtcPsbt.parse", IllegalArgumentException::class, ::psbtInput) { BtcPsbt.parse(it) }

    @Test
    fun theSolanaMessageParserNeverThrows() =
        propertyRun("SolanaMessage.decode", refusal = null, ::solanaMessageInput) { SolanaMessage.decode(it) }

    @Test
    fun theSolanaOffchainParserNeverThrows() =
        propertyRun("SolanaOffchainMessage.parse", refusal = null, ::offchainInput) { SolanaOffchainMessage.parse(it) }

    @Test
    fun theAlgorandDecoderRefusesOnlyWithItsException() =
        propertyRun("AlgoTxnDecoder.decode", AlgoTxnException::class, ::algorandInput) { AlgoTxnDecoder.decode(it) }

    @Test
    fun theTypedDataParserNeverThrows() =
        propertyRun("TypedDataParser.parse", refusal = null, ::typedDataInput) { TypedDataParser.parse(it) }
}

/**
 * Feeds [ITERATIONS] inputs from [input] to [parse], drawn from one [Random] with a fixed seed. An exception is allowed only when
 * it is an instance of [refusal]; a null [refusal] allows none. A failure names the parser and the input in hex.
 */
private fun propertyRun(
    name: String,
    refusal: KClass<out Throwable>?,
    input: (Random) -> ByteArray,
    parse: (ByteArray) -> Unit,
) {
    val random = Random(SEED)
    val started = System.nanoTime()
    repeat(ITERATIONS) {
        val bytes = input(random)
        try {
            parse(bytes)
        } catch (e: Throwable) {
            if (refusal == null || !refusal.isInstance(e)) {
                fail("$name threw ${e.javaClass.name} on ${bytes.size} bytes ${bytes.hex()}: ${e.message}")
            }
        }
    }
    val elapsed = System.nanoTime() - started
    assertTrue(elapsed < BUDGET_NANOS, "$name took ${elapsed / 1_000_000} ms for $ITERATIONS inputs")
}

private fun randomBytes(random: Random, maxSize: Int): ByteArray = random.nextBytes(random.nextInt(maxSize + 1))

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun psbtInput(random: Random): ByteArray {
    val body = randomBytes(random, 2048)
    return if (random.nextBoolean()) PSBT_MAGIC + body else body
}

/** Random bytes, half of them behind a header that stays inside the packet bounds, so the message is read past its first byte. */
private fun solanaMessageInput(random: Random): ByteArray {
    val body = randomBytes(random, SolanaMessage.MAX_BYTES + 64)
    return if (random.nextBoolean()) {
        byteArrayOf(random.nextInt(4).toByte(), random.nextInt(4).toByte(), random.nextInt(6).toByte()) + body
    } else {
        body
    }
}

private fun offchainInput(random: Random): ByteArray {
    val body = randomBytes(random, 1280)
    return if (random.nextBoolean()) byteArrayOf(0xff.toByte()) + body else body
}

/** Random bytes, half of them behind a msgpack map header, so the transaction is read past its first key. */
private fun algorandInput(random: Random): ByteArray {
    val body = randomBytes(random, 1024)
    return if (random.nextBoolean()) byteArrayOf((0x80 or random.nextInt(16)).toByte()) + body else body
}

private const val TYPED_DATA_TEMPLATE = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},""" +
    """{"name":"chainId","type":"uint256"}],"Mail":[{"name":"contents","type":"string"}]},"primaryType":"Mail",""" +
    """"domain":{"name":"Lensly","chainId":1},"message":{"contents":"hello"}}"""

private val JSON_TOKENS = listOf(
    "{", "}", "[", "]", ":", ",", "\"", "\\", "\"types\"", "\"primaryType\"", "\"domain\"", "\"message\"", "\"chainId\"",
    "\"name\"", "\"type\"", "\"uint256\"", "\"address\"", "\"bytes32\"", "\"string\"", "\"bool\"", "\"0x\"",
    "\"0x0000000000000000000000000000000000000000\"", "0x", "-1", "0", "1", "99999999999999999999999999", "true", "null",
    "é", "\uD83D", " ",
)

/** Either a JSON soup of random tokens, or the valid document with a few random edits, so inputs reach the values. */
private fun typedDataInput(random: Random): ByteArray {
    val text = if (random.nextBoolean()) {
        val edited = StringBuilder(TYPED_DATA_TEMPLATE)
        repeat(random.nextInt(1, 4)) {
            val at = random.nextInt(edited.length + 1)
            val cut = random.nextInt(0, minOf(8, edited.length - at) + 1)
            edited.replace(at, at + cut, JSON_TOKENS[random.nextInt(JSON_TOKENS.size)])
        }
        edited.toString()
    } else {
        buildString { repeat(random.nextInt(1, 60)) { append(JSON_TOKENS[random.nextInt(JSON_TOKENS.size)]) } }
    }
    val bytes = text.encodeToByteArray()
    // A few inputs also carry bytes that are not UTF-8 at all.
    return if (random.nextInt(8) == 0) bytes + randomBytes(random, 16) else bytes
}
