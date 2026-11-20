package app.airvault.chains.evm.signer

import app.lensly.core.parse.WireException
import app.lensly.core.registry.Registry
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The typed data of a request body: its unsigned members, and the domain rules that the wire applies as the parser applies them. */
class EvmRequestBodyTest {
    private val reviews = EvmReviewBuilder(Registry(networks = emptyList(), tokens = emptyList()), { _, _ -> error("typed data decodes no call") })

    /** A member a message adds beside its types is not signed. It must reach the review after the wire, so the owner sees it as not signed. */
    @Test
    fun theUnsignedMembersOfATypedMessageReachTheReviewAfterTheWire() {
        val json = """{"types":{"Agent":[{"name":"source","type":"string"}]},"primaryType":"Agent",""" +
            """"domain":{"name":"Exchange"},"message":{"source":"a","note":"hi"}}"""
        val parsed = assertIs<TypedDataParser.Result.Ok>(TypedDataParser.parse(json.toByteArray(Charsets.UTF_8)))

        val decoded = EvmRequestBody.decode(bodyOf(parsed))

        assertEquals(mapOf("note" to "hi"), assertIs<TypedDataParser.Result.Ok>(decoded.typed).unsigned)
        val review = assertIs<EvmReviewResult.Ok>(reviews.review(decoded, SENDER)).review
        assertEquals(listOf("Unsigned: note"), review.sections.single { it.heading == "Not Signed" }.rows.map { it.label })
    }

    /** A domain chain ID that Lensly cannot read in full is refused on the wire, as the parser refuses it. */
    @Test
    fun aDomainChainIdOutsideTheReadRangeIsRefusedOnTheWire() {
        assertRefusedOnTheWire(typedWithChainId(BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE)))
    }

    /** A domain member of a struct type is refused on the wire, as the parser refuses it: the review shows each domain member as one value. */
    @Test
    fun aStructDomainMemberIsRefusedOnTheWire() {
        val types = mapOf(
            EIP712_DOMAIN to listOf(TypedField("inner", "Inner")),
            "Inner" to listOf(TypedField("a", "uint256")),
            "Agent" to listOf(TypedField("source", "string")),
        )
        val domain = TypedValue.StructValue(mapOf("inner" to TypedValue.StructValue(mapOf("a" to TypedValue.IntValue(BigInteger.ONE)))))

        assertRefusedOnTheWire(TypedData(types, "Agent", domain, agentMessage()))
    }

    /** A type name with a colon is read only in a labelled off-chain domain, so the wire refuses it in any other domain. */
    @Test
    fun aColonTypeNameOutsideALabelledDomainIsRefusedOnTheWire() {
        val types = mapOf(
            EIP712_DOMAIN to listOf(TypedField("name", "string")),
            "Hyper:Action" to listOf(TypedField("x", "uint256")),
        )
        val domain = TypedValue.StructValue(mapOf("name" to TypedValue.TextValue("Other")))
        val message = TypedValue.StructValue(mapOf("x" to TypedValue.IntValue(BigInteger.ONE)))

        assertRefusedOnTheWire(TypedData(types, "Hyper:Action", domain, message))
    }

    /** A domain chain ID that does not fit a Long is refused when it is read, not cut down to a chain ID that fits. */
    @Test
    fun aDomainChainIdThatDoesNotFitALongIsNotTruncatedWhenRead() {
        val typed = typedWithChainId(BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE))
        val parsed = ParsedRequest(typedRequest(), tx = null, call = null, typed = TypedDataParser.Result.Ok(typed))

        assertFailsWith<ArithmeticException> { readChainId(parsed) }
    }

    /** The body the sandbox writes for a typed request whose parse is [typed]. */
    private fun bodyOf(typed: TypedDataParser.Result): ByteArray =
        EvmRequestBody.encode(ParsedRequest(typedRequest(), tx = null, call = null, typed = typed))

    private fun assertRefusedOnTheWire(typed: TypedData) {
        assertFailsWith<WireException> { EvmRequestBody.decode(bodyOf(TypedDataParser.Result.Ok(typed))) }
    }

    private fun typedRequest(): EthSignRequest = EthSignRequest(null, SIGN_DATA, DataType.TYPED_DATA, null, emptyList(), null, null, null)

    private fun typedWithChainId(chainId: BigInteger): TypedData = TypedData(
        types = mapOf(EIP712_DOMAIN to listOf(TypedField("chainId", "uint256")), "Agent" to listOf(TypedField("source", "string"))),
        primaryType = "Agent",
        domain = TypedValue.StructValue(mapOf("chainId" to TypedValue.IntValue(chainId))),
        message = agentMessage(),
    )

    private fun agentMessage(): TypedValue.StructValue = TypedValue.StructValue(mapOf("source" to TypedValue.TextValue("a")))

    private companion object {
        val SIGN_DATA = byteArrayOf(1)
        val SENDER = "0x" + "11".repeat(20)
    }
}
