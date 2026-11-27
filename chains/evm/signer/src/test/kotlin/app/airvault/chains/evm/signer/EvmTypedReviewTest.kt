package app.airvault.chains.evm.signer

import app.airvault.abi.AbiDecoder
import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.FieldPath
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.NetworkKind
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
private const val TYPED_UNKNOWN_TOKEN = "0x2222222222222222222222222222222222222222"
private const val TYPED_MAX_UINT256 = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
private const val TYPED_MAX_UINT160 = "1461501637330902918203684832716283019655932542975"
private const val PERMIT2 = "0x000000000022D473030F116dDEE9F6B43aC78BA3"
private const val PERMIT_WARNING = "A signed permit lets a spender move your tokens without a transaction. Scam sites use it to drain wallets."

/** The registry the typed review reads: Ethereum with USDC, and a denylist entry the tests choose. */
private val TYPED_REGISTRY = Registry(
    networks = listOf(NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null)),
    tokens = listOf(TokenEntry(1, TYPED_USDC, "USDC", "USD Coin", 6)),
)

private val TYPED_PATH = listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))

/** An ERC-2612 Permit for [OWNER] on [chainId] for [value] of [token], which is USDC unless the test names another contract. */
private fun permitJson(chainId: Long = 1, value: String = "1000000", token: String = TYPED_USDC): String =
    """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
        """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],""" +
        """"Permit":[{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},""" +
        """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]},"primaryType":"Permit",""" +
        """"domain":{"name":"USD Coin","version":"2","chainId":$chainId,"verifyingContract":"$token"},""" +
        """"message":{"owner":"$OWNER","spender":"$TYPED_SPENDER","value":"$value","nonce":"0","deadline":"1900000000"}}"""

/** A Permit2 PermitSingle of USDC for [amount], with a memo member the Permit2 layout does not have. */
private fun permitSingleWithMemoJson(amount: String): String =
    """{"types":{"PermitSingle":[{"name":"details","type":"PermitDetails"},{"name":"spender","type":"address"},""" +
        """{"name":"sigDeadline","type":"uint256"},{"name":"memo","type":"string"}],""" +
        """"PermitDetails":[{"name":"token","type":"address"},{"name":"amount","type":"uint160"},""" +
        """{"name":"expiration","type":"uint48"},{"name":"nonce","type":"uint48"}]},""" +
        """"primaryType":"PermitSingle","domain":{"name":"Permit2","chainId":1,"verifyingContract":"$PERMIT2"},""" +
        """"message":{"details":{"token":"$TYPED_USDC","amount":"$amount","expiration":"1900000000","nonce":"0"},""" +
        """"spender":"$TYPED_SPENDER","sigDeadline":"1900000000","memo":"hi"}}"""

/** A Permit2 signature transfer of USDC for [amount] that only [TYPED_SPENDER] can submit: the spender is part of what is signed. */
private fun permitTransferFromJson(amount: String = "1000000"): String =
    """{"types":{"PermitTransferFrom":[{"name":"permitted","type":"TokenPermissions"},{"name":"spender","type":"address"},""" +
        """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}],""" +
        """"TokenPermissions":[{"name":"token","type":"address"},{"name":"amount","type":"uint256"}]},""" +
        """"primaryType":"PermitTransferFrom","domain":{"name":"Permit2","chainId":1,"verifyingContract":"$PERMIT2"},""" +
        """"message":{"permitted":{"token":"$TYPED_USDC","amount":"$amount"},"spender":"$TYPED_SPENDER",""" +
        """"nonce":"0","deadline":"1900000000"}}"""

/** The batch form of [permitTransferFromJson] with two tokens. */
private fun permitBatchTransferFromJson(): String =
    """{"types":{"PermitBatchTransferFrom":[{"name":"permitted","type":"TokenPermissions[]"},{"name":"spender","type":"address"},""" +
        """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}],""" +
        """"TokenPermissions":[{"name":"token","type":"address"},{"name":"amount","type":"uint256"}]},""" +
        """"primaryType":"PermitBatchTransferFrom","domain":{"name":"Permit2","chainId":1,"verifyingContract":"$PERMIT2"},""" +
        """"message":{"permitted":[{"token":"$TYPED_USDC","amount":"5"},{"token":"$TYPED_USDC","amount":"6"}],""" +
        """"spender":"$TYPED_SPENDER","nonce":"0","deadline":"1900000000"}}"""

/** An ERC-2612 Permit of USDC for [value], with a memo member the Permit layout does not have. */
private fun permitWithMemoJson(value: String): String =
    """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
        """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],""" +
        """"Permit":[{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},""" +
        """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"},{"name":"memo","type":"string"}]},""" +
        """"primaryType":"Permit","domain":{"name":"USD Coin","version":"2","chainId":1,"verifyingContract":"$TYPED_USDC"},""" +
        """"message":{"owner":"$OWNER","spender":"$TYPED_SPENDER","value":"$value","nonce":"0","deadline":"1900000000","memo":"hi"}}"""

/** An ERC-2612 Permit of USDC for [OWNER] whose domain names no chain, so the signature is valid on every chain. */
private fun chainlessPermitJson(): String =
    """{"types":{"Permit":[{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},""" +
        """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]},"primaryType":"Permit",""" +
        """"domain":{"name":"USD Coin","version":"2","verifyingContract":"$TYPED_USDC"},""" +
        """"message":{"owner":"$OWNER","spender":"$TYPED_SPENDER","value":"1000000","nonce":"0","deadline":"1900000000"}}"""

/** A typed message that is not a permit: a Mail struct under a domain with no verifying contract. */
private fun mailJson(contents: String = "Hello"): String =
    """{"types":{"Mail":[{"name":"from","type":"address"},{"name":"contents","type":"string"},{"name":"amount","type":"uint256"}]},""" +
        """"primaryType":"Mail","domain":{"name":"Mailer","chainId":1},""" +
        """"message":{"from":"$OWNER","contents":"$contents","amount":"42"}}"""

private fun typedRequest(json: String, chainId: Long? = 1): ParsedRequest = EvmRequestParser.parsedOf(
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
    fun aPermit2SignatureTransferShowsItsRealSpenderNeverAnyone() {
        val single = typedReview(permitTransferFromJson())
        val batch = typedReview(permitBatchTransferFromJson())

        assertEquals(TYPED_SPENDER, rowOf(single, "Summary", "Spender").value.text())
        assertEquals(TYPED_SPENDER, rowOf(batch, "Summary", "Spender").value.text())
        for (review in listOf(single, batch)) {
            assertTrue(review.sections.flatMap { it.rows }.none { "Anyone" in it.value.text() }, "no row says anyone may submit")
        }
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
    fun aPermitWithAnExtraMemberStillWarnsAgainstAnUnlimitedApproval() {
        val review = typedReview(permitWithMemoJson(value = TYPED_MAX_UINT256))

        assertTrue(rowOf(review, "Summary", "Amount").value.text().startsWith("Unlimited"))
        val warning = review.warnings.single()
        assertEquals(Danger.DANGER, warning.level)
        assertEquals("Unlimited approval: this contract can spend all your USDC", warning.message)
    }

    @Test
    fun aPermit2WithAnExtraMemberStillWarnsAgainstAnUnlimitedApproval() {
        val review = typedReview(permitSingleWithMemoJson(amount = TYPED_MAX_UINT160))

        assertTrue(rowOf(review, "Summary", "Amount").value.text().startsWith("Unlimited"))
        val warning = review.warnings.single()
        assertEquals(Danger.DANGER, warning.level)
        assertEquals("Unlimited approval: this contract can spend all your USDC", warning.message)
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
    fun aTypedMessageThatIsNotAPermitIsShownFieldByFieldWithNoSummaryOrWarning() {
        val review = typedReview(mailJson())

        assertEquals("Mail", review.title)
        assertEquals("Mailer", rowOf(review, "Domain", "Name").value.text())
        assertEquals("1 (Ethereum)", rowOf(review, "Domain", "Chain ID").value.text())
        assertEquals(OWNER, rowOf(review, "Message", "from (address)").value.text().lowercase())
        assertEquals("Hello", rowOf(review, "Message", "contents (string)").value.text())
        assertEquals("42", rowOf(review, "Message", "amount (uint256)").value.text())
        assertTrue(review.sections.none { it.heading == "Summary" }, "no summary is guessed for a message that is not a permit")
        assertTrue(review.warnings.isEmpty())
    }

    /** U+E0001 is a language tag. It draws nothing, so the string reads "Hello" while the signed bytes hold more: the string shows its bytes. */
    @Test
    fun aTypedStringWithAnInvisibleTagCharacterShowsItsBytes() {
        val review = typedReview(mailJson(contents = "Hello" + String(Character.toChars(0xE0001))))

        assertEquals("0x48656c6c6ff3a08081 (not printable)", rowOf(review, "Message", "contents (string)").value.text())
    }

    @Test
    fun aPermitOnAContractTheRegistryLacksShowsTheRawAmountAndWarns() {
        val review = typedReview(permitJson(token = TYPED_UNKNOWN_TOKEN))

        assertEquals("Unknown token $TYPED_UNKNOWN_TOKEN", rowOf(review, "Summary", "Token").value.text())
        assertEquals("1000000 (decimals unknown)", rowOf(review, "Summary", "Amount").value.text())
        val warning = review.warnings.single()
        assertEquals(Danger.CAUTION, warning.level)
        assertEquals("This contract is not in Lensly's token registry, so Lensly cannot check its name or decimals.", warning.message)
        assertEquals("I have checked the chain ID and the contract.", warning.acknowledge)
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
    fun chainlessTypedDataIsCheckedAgainstTheDenylistEntryOfAnotherChain() {
        val registry = TYPED_REGISTRY.withDenylist(
            listOf(DenylistEntry(chainId = 137, primaryType = "Permit", severity = DenylistSeverity.WARN, reason = PERMIT_WARNING)),
        )

        val result = EvmReviewBuilder(registry, { calldata, params -> AbiDecoder.decode(calldata, params) })
            .review(typedRequest(chainlessPermitJson(), chainId = null), sender = OWNER)

        val denylist = assertIs<EvmReviewResult.Ok>(result).review.warnings.single { it.message == PERMIT_WARNING }
        assertEquals(Danger.DANGER, denylist.level)
    }

    @Test
    fun chainlessTypedDataUnderAnotherRequestChainIsCheckedAgainstTheDenylistEntryOfEveryChain() {
        val registry = TYPED_REGISTRY.withDenylist(
            listOf(DenylistEntry(chainId = 137, primaryType = "Permit", severity = DenylistSeverity.WARN, reason = PERMIT_WARNING)),
        )

        val result = EvmReviewBuilder(registry, { calldata, params -> AbiDecoder.decode(calldata, params) })
            .review(typedRequest(chainlessPermitJson(), chainId = 1), sender = OWNER)

        val denylist = assertIs<EvmReviewResult.Ok>(result).review.warnings.single { it.message == PERMIT_WARNING }
        assertEquals(Danger.DANGER, denylist.level)
    }

    @Test
    fun aChainlessDomainUnderAnotherRequestChainShowsAnyChainAndIsReadOnTheRequestChain() {
        val review = typedReview(chainlessPermitJson(), chainId = 1)

        assertEquals(NetworkLabel("Any Chain", testnet = false, detail = null), review.network)
        assertEquals(ChainId("eip155:1"), review.chain)
        assertTrue(review.warnings.any { it.code == "evm.typed.chain" && it.level == Danger.CAUTION })
    }

    @Test
    fun aTypedDomainOnAnotherChainThanTheRequestIsSignableAndItsChainIsNamed() {
        val result = EvmReviewBuilder(TYPED_REGISTRY, { calldata, params -> AbiDecoder.decode(calldata, params) })
            .review(typedRequest(permitJson(chainId = 1), chainId = 10143), sender = OWNER)

        val review = assertIs<EvmReviewResult.Ok>(result).review
        assertTrue((review.facts + review.sections.flatMap { it.rows }).any { it.label.startsWith("Signature for Chain 1 (") })
    }

    @Test
    fun aTypedDomainOnAnotherChainThanTheRequestIsReadOnItsOwnChain() {
        // The request names Arbitrum One, but the domain names Ethereum. The network, the addresses and the token come from Ethereum.
        val registry = Registry(
            networks = listOf(
                NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
                NetworkEntry(42161, "Arbitrum One", "ETH", 18, FeeModel.L1, "Arbiscan", testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
            ),
            tokens = listOf(
                TokenEntry(1, TYPED_USDC, "USDC", "USD Coin", 6),
                TokenEntry(42161, TYPED_USDC, "USDC.e", "Bridged USDC", 6),
            ),
        )

        val review = assertIs<EvmReviewResult.Ok>(
            EvmReviewBuilder(registry, { calldata, params -> AbiDecoder.decode(calldata, params) })
                .review(typedRequest(permitJson(chainId = 1), chainId = 42161), sender = OWNER),
        ).review

        assertEquals("Ethereum", review.network.name)
        assertEquals(ChainId("eip155:1"), review.chain)
        assertEquals(ChainId("eip155:1"), (rowOf(review, "Summary", "Spender").value as DisplayValue.Address).chain)
        assertEquals("USD Coin (USDC) $TYPED_USDC_CHECKSUM", rowOf(review, "Summary", "Token").value.text())
        assertEquals("1 USDC", rowOf(review, "Summary", "Amount").value.text())
    }

    @Test
    fun aChainlessPermitDoesNotResolveItsTokenOnMainnetAndShowsTheRawAmount() {
        // The domain names no chain, so the signature is valid on every chain. USDC is not looked up on chain 1, where the request is read.
        val result = EvmReviewBuilder(TYPED_REGISTRY, { calldata, params -> AbiDecoder.decode(calldata, params) })
            .review(typedRequest(chainlessPermitJson(), chainId = null), sender = OWNER)

        val review = assertIs<EvmReviewResult.Ok>(result).review
        assertEquals("Unknown token $TYPED_USDC_CHECKSUM", rowOf(review, "Summary", "Token").value.text())
        assertEquals("1000000 (decimals unknown)", rowOf(review, "Summary", "Amount").value.text())
        assertTrue(review.warnings.any { it.message.startsWith("This contract is not in Lensly's token registry") })
    }
}
