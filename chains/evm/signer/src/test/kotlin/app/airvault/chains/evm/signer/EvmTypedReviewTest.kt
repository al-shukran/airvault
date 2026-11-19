package app.airvault.chains.evm.signer

import app.airvault.abi.AbiDecoder
import app.airvault.core.model.Danger
import app.airvault.core.model.FieldPath
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val OWNER = "0x0bf93ea5a1fa4ce3dd22c7ffd314462d3869777f"
private const val TYPED_SPENDER = "0x1111111111111111111111111111111111111111"
private const val TYPED_USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
private const val TYPED_USDC_CHECKSUM = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val TYPED_MAX_UINT256 = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
private const val PERMIT_WARNING = "A signed permit lets a spender move your tokens without a transaction. Scam sites use it to drain wallets."

/** The registry the typed review reads: Ethereum with USDC, and a denylist entry the tests choose. */
private val TYPED_REGISTRY = Registry(
    networks = listOf(NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", testnet = false)),
    tokens = listOf(TokenEntry(1, TYPED_USDC, "USDC", "USD Coin", 6)),
)

private val TYPED_PATH = listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))

/** An ERC-2612 Permit for [OWNER] on [chainId] for [value] of USDC. */
private fun permitJson(chainId: Long = 1, value: String = "1000000"): String =
    """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
        """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],""" +
        """"Permit":[{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},""" +
        """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]},"primaryType":"Permit",""" +
        """"domain":{"name":"USD Coin","version":"2","chainId":$chainId,"verifyingContract":"$TYPED_USDC"},""" +
        """"message":{"owner":"$OWNER","spender":"$TYPED_SPENDER","value":"$value","nonce":"0","deadline":"1900000000"}}"""

private fun typedRequest(json: String, chainId: Long = 1): ParsedRequest = EvmRequestParser.parsedOf(
    EthSignRequest(
        requestId = null,
        signData = json.toByteArray(Charsets.UTF_8),
        dataType = DataType.TYPED_DATA,
        chainId = chainId,
        derivationPath = TYPED_PATH,
        sourceFingerprint = null,
        address = null,
        origin = "dapp.example",
    ),
)

private fun typedReview(json: String, registry: Registry = TYPED_REGISTRY, chainId: Long = 1): Review {
    val result = EvmReviewBuilder(registry, { calldata, params -> AbiDecoder.decode(calldata, params) })
        .review(typedRequest(json, chainId), sender = OWNER)
    val review = assertIs<EvmReviewResult.Ok>(result).review
    assertTrue(review.unaccounted().isEmpty(), "every decoded field is shown: ${review.unaccounted()}")
    return review
}

/** The row of [label] in the section [heading]. */
private fun rowOf(review: Review, heading: String, label: String): ReviewRow =
    review.sections.single { it.heading == heading }.rows.single { it.label == label }

class EvmTypedReviewTest {
    @Test
    fun aPermitIsReviewedWithItsTokenSpenderAmountDeadlineAndDomain() {
        val review = typedReview(permitJson())

        assertEquals("Permit", review.title)
        assertEquals("Typed data (EIP-712)", rowOf(review, "Request", "Type").value.text())
        assertEquals("USD Coin", rowOf(review, "Domain", "Name").value.text())
        assertEquals("2", rowOf(review, "Domain", "Version").value.text())
        assertEquals("1 (Ethereum)", rowOf(review, "Domain", "Chain ID").value.text())
        assertEquals("USD Coin (USDC) $TYPED_USDC_CHECKSUM", rowOf(review, "Summary", "Token").value.text())
        assertEquals(TYPED_SPENDER, rowOf(review, "Summary", "Spender").value.text())
        assertEquals("1 USDC", rowOf(review, "Summary", "Amount").value.text())
        assertEquals("17 Mar 2030, 17:46 UTC", rowOf(review, "Summary", "Deadline").value.text())
        assertTrue(review.warnings.isEmpty())
    }

    @Test
    fun anUnlimitedPermitWarnsAgainstAnUnlimitedApprovalAsItDoesToday() {
        val review = typedReview(permitJson(value = TYPED_MAX_UINT256))

        assertTrue(rowOf(review, "Summary", "Amount").value.text().startsWith("Unlimited"))
        val warning = review.warnings.single()
        assertEquals("Unlimited approval: this contract can spend all your USDC", warning.message)
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(FieldPath("eip712.value"), warning.field)
        assertEquals("I understand this approval is unlimited.", warning.acknowledge)
    }

    @Test
    fun aPermitOnTheWarnDenylistShowsItsReasonAndNeedsItsCheck() {
        val registry = TYPED_REGISTRY.withDenylist(
            listOf(DenylistEntry(primaryType = "Permit", severity = DenylistSeverity.WARN, reason = PERMIT_WARNING)),
        )

        val review = typedReview(permitJson(), registry)

        val denylist = review.warnings.single()
        assertEquals(PERMIT_WARNING, denylist.message)
        assertEquals(Danger.DANGER, denylist.level)
        assertEquals("I understand this request matches a known-dangerous request.", denylist.acknowledge)
    }

    @Test
    fun aPermitOnTheBlockDenylistIsRefusedWithItsReason() {
        val registry = TYPED_REGISTRY.withDenylist(
            listOf(DenylistEntry(primaryType = "Permit", severity = DenylistSeverity.BLOCK, reason = "Blocked permit.")),
        )

        val result = EvmReviewBuilder(registry, { calldata, params -> AbiDecoder.decode(calldata, params) })
            .review(typedRequest(permitJson()), sender = OWNER)

        assertEquals("Blocked permit.", assertIs<EvmReviewResult.Refused>(result).reason)
    }

    @Test
    fun aTypedDomainOnAnotherChainThanTheRequestIsRefused() {
        val result = EvmReviewBuilder(TYPED_REGISTRY, { calldata, params -> AbiDecoder.decode(calldata, params) })
            .review(typedRequest(permitJson(chainId = 1), chainId = 10143), sender = OWNER)

        assertEquals(
            "The typed data is for chain 1, but the request names chain 10143",
            assertIs<EvmReviewResult.Refused>(result).reason,
        )
    }
}
