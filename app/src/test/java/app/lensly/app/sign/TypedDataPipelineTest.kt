package app.lensly.app.sign

import app.lensly.core.evm.EvmFixtures
import app.lensly.core.parse.InProcessParser
import app.lensly.core.parse.ParsedRequest
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.Registry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.shippedRegistry
import app.lensly.ui.sign.ReviewCheck
import app.lensly.ui.sign.TypedRow
import app.lensly.ui.sign.unlimitedApprovalWarning
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WALLET_ID = "typed-wallet"
private val WALLET = HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art"))
private val VIEW = WalletView(
    id = WALLET_ID,
    masterFingerprint = WALLET.masterFingerprint,
    accountCount = 2,
    autoNameBase = "Seed Wallet",
    addressAt = { WALLET.address(it) },
)
private val REQUEST_ID = ByteArray(16) { (it + 1).toByte() }
private val OWNER: String = WALLET.address(0)
private const val SPENDER = "0x1111111111111111111111111111111111111111"
private const val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
private const val USDC_CHECKSUM = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val MAX_UINT256 = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
private const val PERMIT_REASON =
    "A signed permit lets a spender move your tokens without a transaction. Scam sites use it to drain wallets."
private const val UNKNOWN_CHAIN_REFUSAL =
    "The typed data is for chain 999999, which is not in Lensly's network registry, so it is refused."

/** The shipped registry, so Ethereum and USDC are known. Its Permit entry refuses a Permit. */
private val SHIPPED: Registry = shippedRegistry()

/** The shipped registry without its denylist, so a Permit reaches review. */
private val OPEN: Registry = SHIPPED.withDenylist(emptyList())

private val SHIPPED_PIPELINE = SignPipeline(SHIPPED, InProcessParser)
private val OPEN_PIPELINE = SignPipeline(OPEN, InProcessParser)

/** An ERC-2612 Permit for [OWNER], on [chainId] in its domain, for [value] of [token]. */
private fun permitDoc(
    chainId: Long = 1,
    value: String = "1000000",
    token: String = USDC,
): String = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
    """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],""" +
    """"Permit":[{"name":"owner","type":"address"},{"name":"spender","type":"address"},{"name":"value","type":"uint256"},""" +
    """{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]},"primaryType":"Permit",""" +
    """"domain":{"name":"USD Coin","version":"2","chainId":$chainId,"verifyingContract":"$token"},""" +
    """"message":{"owner":"$OWNER","spender":"$SPENDER","value":"$value","nonce":"0","deadline":"1900000000"}}"""

/** A Permit2 PermitSingle for [OWNER] of [token] for [amount], which expires at 1900000000. */
private fun permitSingleDoc(token: String = USDC, amount: String = "1000000", chainId: Long = 1): String =
    """{"types":{"PermitSingle":[{"name":"details","type":"PermitDetails"},{"name":"spender","type":"address"},""" +
        """{"name":"sigDeadline","type":"uint256"}],"PermitDetails":[{"name":"token","type":"address"},""" +
        """{"name":"amount","type":"uint160"},{"name":"expiration","type":"uint48"},{"name":"nonce","type":"uint48"}]},""" +
        """"primaryType":"PermitSingle","domain":{"name":"Permit2","chainId":$chainId,""" +
        """"verifyingContract":"0x000000000022d473030f116ddee9f6b43ac78ba3"},""" +
        """"message":{"details":{"token":"$token","amount":"$amount","expiration":"1900000000","nonce":"0"},""" +
        """"spender":"$SPENDER","sigDeadline":"1900000000"}}"""

/** The Mail example of the standard, on chain 1. */
private const val MAIL = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"chainId","type":"uint256"}],""" +
    """"Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],""" +
    """"Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},""" +
    """"primaryType":"Mail","domain":{"name":"Ether Mail","chainId":1},""" +
    """"message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},""" +
    """"to":{"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},"contents":"Hello, Bob!"}}"""

private fun accountPath(index: Int) = listOf(
    PathStep(44, true),
    PathStep(60, true),
    PathStep(0, true),
    PathStep(0, false),
    PathStep(index, false),
)

/** The request of [json] as a wallet sends it, parsed the way the Parser Sandbox parses it. */
private fun parsedOf(json: String, chainId: Long? = 1): ParsedRequest = InProcessParser.parsedOf(
    EthSignRequest(
        requestId = REQUEST_ID,
        signData = json.toByteArray(Charsets.UTF_8),
        dataType = DataType.TYPED_DATA,
        chainId = chainId,
        derivationPath = accountPath(0),
        sourceFingerprint = WALLET.masterFingerprint,
        address = null,
        origin = "metamask",
    ),
)

private fun prepared(pipeline: SignPipeline, json: String, chainId: Long? = 1): Prepared =
    pipeline.prepare(parsedOf(json, chainId), VIEW)

private fun ready(json: String, pipeline: SignPipeline = OPEN_PIPELINE, chainId: Long? = 1): Prepared.Ready =
    assertIs<Prepared.Ready>(prepared(pipeline, json, chainId))

class TypedDataPipelineTest {

    @Test
    fun aPermitIsReviewedWithItsTokenSpenderAmountDeadlineAndDomain() {
        val review = ready(permitDoc()).review

        assertEquals("Permit", review.function)
        assertEquals("Typed data (EIP-712)", review.txType)
        val summary = review.typed!!.summary.toMap()
        assertEquals("USD Coin (USDC) $USDC_CHECKSUM", summary["Token"])
        assertEquals(SPENDER, summary["Spender"])
        assertEquals("1 USDC", summary["Amount"])
        assertEquals("17 Mar 2030, 17:46 UTC", summary["Deadline"])
        assertEquals(listOf("Name" to "USD Coin", "Version" to "2", "Chain ID" to "1 (Ethereum)"), review.typed!!.domain.take(3))
        assertTrue(review.warnings.isEmpty())
        assertTrue(review.requiredChecks.isEmpty())
    }

    @Test
    fun anUnlimitedPermitIsFlaggedAndWarnedAgainstAsAnUnlimitedApproval() {
        val review = ready(permitDoc(value = MAX_UINT256)).review

        assertTrue(review.typed!!.summary.toMap().getValue("Amount").startsWith("Unlimited"))
        assertEquals(listOf(unlimitedApprovalWarning("USDC")), review.warnings)
    }

    @Test
    fun aPermitMatchingTheShippedWarnEntryReachesReviewWithItsReasonAndNeedsTheConfirmationBeforeSigning() {
        val review = ready(permitDoc(), pipeline = SHIPPED_PIPELINE).review

        assertEquals(listOf(PERMIT_REASON), review.denylisted.map { it.reason })
        assertEquals(DenylistSeverity.WARN, SHIPPED.typedDenylistMatches(1, USDC, "Permit").single().severity)
        assertTrue(ReviewCheck.DENYLIST in review.requiredChecks)
        // HOLD TO SIGN stays off until the owner has ticked the warning's confirmation, as it does for a transaction.
        assertFalse(review.canApprove(everyFactShown = true, checked = emptySet()))
        assertTrue(review.canApprove(everyFactShown = true, checked = setOf(ReviewCheck.DENYLIST)))
    }

    @Test
    fun aBlockEntryMatchingTypedDataRefusesItBeforeItsReviewWithTheEntryReason() {
        val blocked = OPEN.withDenylist(
            listOf(DenylistEntry(primaryType = "Permit", severity = DenylistSeverity.BLOCK, reason = "Blocked permit.")),
        )

        val refused = assertIs<Prepared.Refused>(prepared(SignPipeline(blocked, InProcessParser), permitDoc()))

        assertEquals("Blocked permit.", refused.reason)
    }

    @Test
    fun aTypedDataDomainOnAnotherChainThanTheRequestIsRefused() {
        val refused = assertIs<Prepared.Refused>(prepared(OPEN_PIPELINE, permitDoc(chainId = 1), chainId = 10143))

        assertEquals("The typed data is for chain 1, but the request names chain 10143", refused.reason)
    }

    @Test
    fun aTypedMessageOfAChainOutsideTheRegistryIsRefusedBeforeItsReview() {
        val refused = assertIs<Prepared.Refused>(
            prepared(SHIPPED_PIPELINE, permitSingleDoc(chainId = 999_999), chainId = 999_999),
        )

        assertEquals(UNKNOWN_CHAIN_REFUSAL, refused.reason)
    }

    @Test
    fun aMessageOfAnotherTypeOnAChainOutsideTheRegistryIsRefusedToo() {
        val mailOnUnknownChain = MAIL.replace("\"chainId\":1}", "\"chainId\":999999}")

        val refused = assertIs<Prepared.Refused>(prepared(SHIPPED_PIPELINE, mailOnUnknownChain, chainId = 999_999))

        assertEquals(UNKNOWN_CHAIN_REFUSAL, refused.reason)
    }

    @Test
    fun aPermitSingleForAnUnregisteredTokenIsReviewedAsUnknownAndNeedsTheRegistryCheck() {
        val ready = ready(permitSingleDoc(token = SPENDER), pipeline = SHIPPED_PIPELINE)

        val typed = assertNotNull(ready.review.typed)
        assertTrue(typed.hasUnknownToken)
        assertEquals("Unknown token $SPENDER", typed.summary.toMap().getValue("Token"))
        assertEquals("1000000 (decimals unknown)", typed.summary.toMap().getValue("Amount"))
        assertTrue(ready.review.needsRegistryConfirmation)
        assertTrue(ReviewCheck.REGISTRY in ready.review.requiredChecks)
    }

    @Test
    fun aMessageOfAnotherTypeReachesReviewWithEveryFieldNested() {
        val review = ready(MAIL, pipeline = SHIPPED_PIPELINE).review

        assertEquals("Mail", review.function)
        assertTrue(review.typed!!.summary.isEmpty())
        assertEquals(
            listOf(
                TypedRow(0, "from", "Person", null),
                TypedRow(1, "name", "string", "Cow"),
                TypedRow(1, "wallet", "address", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
                TypedRow(0, "to", "Person", null),
                TypedRow(1, "name", "string", "Bob"),
                TypedRow(1, "wallet", "address", "0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"),
                TypedRow(0, "contents", "string", "Hello, Bob!"),
            ),
            review.typed!!.fields,
        )
        assertEquals("Cow", review.params.single { it.first == "from.name" }.second)
    }

    @Test
    fun aTypedDocumentThatDoesNotParseIsRefusedWithTheParsersReason() {
        val refused = assertIs<Prepared.Refused>(prepared(SHIPPED_PIPELINE, """{"types":{},"message":{}}"""))

        assertEquals("the document is missing primaryType", refused.reason)
    }

    @Test
    fun aSignedPermitRecoversToTheAccountOverTheDigestWithVOf27Or28() {
        val ready = ready(permitDoc())
        val key = WALLET.privateKey(ready.accountIndex)
        val frames = OPEN_PIPELINE.sign(ready, key)

        assertEquals(1, frames.size)
        val scanner = UrScanner()
        assertTrue(scanner.receive(frames.single()))
        val ur = assertNotNull(scanner.result())
        assertEquals("eth-signature", ur.type)
        val fields = (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (field, value) ->
            (field as Cbor.Uint).value to value
        }
        assertTrue(((fields.getValue(1L) as Cbor.Tagged).value as Cbor.Bytes).value.contentEquals(REQUEST_ID))
        val signature = (fields.getValue(2L) as Cbor.Bytes).value
        assertEquals(65, signature.size)
        assertTrue(signature[64] == 27.toByte() || signature[64] == 28.toByte())

        val recovered = EvmFixtures.recoveredAddress(
            assertNotNull(ready.typed).digest(),
            signature.copyOfRange(0, 32),
            signature.copyOfRange(32, 64),
            signature[64].toInt() - 27,
        )
        assertEquals(EvmFixtures.normalize(VIEW.addressAt(0)), recovered)
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun aDenylistWarningOnATypedRequestIsShownAndNeedsItsOwnCheck() {
        val withWarning = OPEN.withDenylist(
            listOf(DenylistEntry(primaryType = "Permit", severity = DenylistSeverity.WARN, reason = "Warned permit.")),
        )
        val pipeline = SignPipeline(withWarning, InProcessParser)

        val review = ready(permitDoc(), pipeline = pipeline).review

        assertEquals(listOf("Warned permit."), review.denylisted.map { it.reason })
        assertTrue(ReviewCheck.DENYLIST in review.requiredChecks)
    }
}
