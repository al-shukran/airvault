package app.airvault.chains.evm.signer

import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.stopsSigning
import app.airvault.core.model.text
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * Real off-chain payloads of Hyperliquid and AsterDex, parsed and reviewed. Sources, per payload:
 * Hyperliquid: hyperliquid-python-sdk, hyperliquid/utils/signing.py (l1_payload, user_signed_payload, sign_user_signed_action) and
 * hyperliquid/exchange.py (usd_transfer, withdraw_from_bridge, approve_agent). Connection ID of the L1 fixtures: the dummy action of
 * tests/signing_test.py, computed from its msgpack encoding, and the production hash asserted in test_phantom_agent_creation_matches_production.
 * AsterDex: github.com/asterdex/api-docs, V3 futures doc, "Register and Approve Agent" and "Supported Signing Algorithms" (chainId=1666).
 * Lighter: github.com/elliottech/lighter-go, types/txtypes/utils.go (TemplateChangePubKey) and change_pub_key.go (GetL1SignatureBody).
 */

private const val SENDER = "0x14791697260E4c9A71f18484C9f997B308e59325"
private const val ZERO = "0x0000000000000000000000000000000000000000"
private const val AGENT = "0x5e9ee1089755c3435139848e47e6635505d5a13a"
private const val DUMMY_CONNECTION_ID = "0xf528daee6a0bd11407b483cfcd9a48c56884180b70ee86f124053e5fc1bf4d57"
private const val PRODUCTION_CONNECTION_ID = "0x0fcbeda5ae3c4950a548021552a4fea2226858c4453571bf3f24ba017eac2908"
private const val ARBITRUM = 42161L
private const val BNB = 56L
private const val HYPERLIQUID_L1_LABEL = "Off-Chain Signature for Hyperliquid"
private const val ASTER_LABEL = "Off-Chain Signature for AsterDex"

private const val ASTER_APPROVE_MESSAGE = "user=$SENDER&nonce=1748310859508867&agentName=example&agentAddress=$AGENT" +
    "&expired=1800000000000&signatureChainId=56&canSpotTrade=true&canPerpTrade=true&canWithdraw=false&ipWhitelist="
private const val ASTER_MASTER_MESSAGE = "childAddress=$AGENT&name=example&nonce=1748310859508867&user=$SENDER" +
    "&childSignature=0xa7a7467a09f42c44e5b05a6514d3a061edf058902fee3eee71b96309c98abd4157e489047f4f7ee69b582554576c551387d1229604345811d2721a1f116a8ac01c"

/** The Lighter API key message for public key bytes 01..28 (40 bytes), nonce 5, account 3 and API key index 2, as its template fills it. */
private val LIGHTER_MESSAGE: String = "Register Lighter Account\n\npubkey: 0x" + (1..40).joinToString("") { "%02x".format(it) } +
    "\nnonce: 0x0000000000000005\naccount index: 0x0000000000000003\napi key index: 0x0000000000000002\n" +
    "Only sign this message for a trusted client!"

private const val DOMAIN_TYPES = """{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
    """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}"""

/** The L1 phantom agent: the typed message of a Hyperliquid L1 action, on the Exchange domain of chain 1337. */
private fun l1Agent(source: String, connectionId: String, extra: String = "") =
    """{"types":{"Agent":[{"name":"source","type":"string"},{"name":"connectionId","type":"bytes32"}],"EIP712Domain":[$DOMAIN_TYPES]},""" +
        """"primaryType":"Agent","domain":{"chainId":1337,"name":"Exchange","verifyingContract":"$ZERO","version":"1"},""" +
        """"message":{"source":"$source","connectionId":"$connectionId"$extra}}"""

/** A user-signed Hyperliquid action on the HyperliquidSignTransaction domain of chain 421614, as its SDK builds it. */
private fun userSigned(primaryType: String, fields: String, message: String, version: String = "1") =
    """{"types":{"$primaryType":[$fields],"EIP712Domain":[$DOMAIN_TYPES]},"primaryType":"$primaryType",""" +
        """"domain":{"name":"HyperliquidSignTransaction","version":"$version","chainId":421614,"verifyingContract":"$ZERO"},""" +
        """"message":{$message}}"""

private const val TRANSFER_FIELDS = """{"name":"hyperliquidChain","type":"string"},{"name":"destination","type":"string"},""" +
    """{"name":"amount","type":"string"},{"name":"time","type":"uint64"}"""

/** The usdSend action as exchange.py posts it: its type and signatureChainId are members the typed fields do not declare. */
private val USD_SEND = userSigned(
    "HyperliquidTransaction:UsdSend",
    TRANSFER_FIELDS,
    """"type":"usdSend","destination":"$AGENT","amount":"1","time":1687816341423,"signatureChainId":"0x66eee","hyperliquidChain":"Testnet"""",
)

/** An AsterDex typed message: one string member, msg, on the AsterSignTransaction domain of [chainId]. */
private fun asterMessage(chainId: Int, msg: String) =
    """{"types":{"EIP712Domain":[$DOMAIN_TYPES],"Message":[{"name":"msg","type":"string"}]},"primaryType":"Message",""" +
        """"domain":{"name":"AsterSignTransaction","version":"1","chainId":$chainId,"verifyingContract":"$ZERO"},""" +
        """"message":{"msg":"$msg"}}"""

private val REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", testnet = false),
        NetworkEntry(BNB, "BNB Smart Chain", "BNB", 18, FeeModel.L1, "BscScan", testnet = false),
        NetworkEntry(ARBITRUM, "Arbitrum One", "ETH", 18, FeeModel.L1, "Arbiscan", testnet = false),
    ),
    tokens = emptyList(),
)

private val REVIEWS = EvmReviewBuilder(REGISTRY, { _, _ -> error("a typed message or a personal message decodes no call") })

private fun reviewOf(signData: ByteArray, chainId: Long?, dataType: DataType = DataType.TYPED_DATA): EvmReviewResult =
    REVIEWS.review(
        EvmRequestParser.parsedOf(
            EthSignRequest(
                requestId = ByteArray(16) { (it + 1).toByte() },
                signData = signData,
                dataType = dataType,
                chainId = chainId,
                derivationPath = emptyList(),
                sourceFingerprint = null,
                address = null,
                origin = null,
            ),
        ),
        SENDER,
    )

private fun reviewOfJson(json: String, chainId: Long?): EvmReviewResult = reviewOf(json.toByteArray(Charsets.UTF_8), chainId)

private fun reviewed(json: String, chainId: Long?): Review = assertIs<EvmReviewResult.Ok>(reviewOfJson(json, chainId)).review

private fun refusedReason(json: String, chainId: Long?): String = assertIs<EvmReviewResult.Refused>(reviewOfJson(json, chainId)).reason

private fun rowsOf(review: Review): List<ReviewRow> = review.facts + review.sections.flatMap { it.rows }

/** Asserts that a row shows [expected] whole, and that no row abbreviates a value with an ellipsis. */
private fun assertShownWhole(review: Review, expected: String) {
    val texts = rowsOf(review).map { it.value.text() }
    assertTrue(expected in texts, "no row shows the full text")
    assertFalse(texts.any { "…" in it || "..." in it }, "a row is abbreviated with an ellipsis")
}

private fun assertNoStoppingWarning(review: Review) {
    assertTrue(review.warnings.none { it.stopsSigning }, "the review has a warning that stops signing: ${review.warnings}")
}

private fun noteOf(review: Review, label: String): ReviewRow =
    assertNotNull(review.sections.first().rows.singleOrNull { it.label == label }, "no note row named $label")

class OffChainSignatureReviewTest {

    @Test
    fun hyperliquidL1ActionReviewsOnItsOwnDomainWithTheWholeConnectionId() {
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID), ARBITRUM)

        assertEquals("Agent", review.title)
        assertEquals(HYPERLIQUID_L1_LABEL, noteOf(review, HYPERLIQUID_L1_LABEL).label)
        assertShownWhole(review, DUMMY_CONNECTION_ID)
        assertTrue(rowsOf(review).any { it.value.text() == "1337 (off-chain domain, not a network)" })
        assertNoStoppingWarning(review)
    }

    @Test
    fun hyperliquidL1ActionOfTheProductionOrderShowsItsWholeConnectionIdOnTestnetToo() {
        assertShownWhole(reviewed(l1Agent("a", PRODUCTION_CONNECTION_ID), ARBITRUM), PRODUCTION_CONNECTION_ID)
        assertShownWhole(reviewed(l1Agent("b", DUMMY_CONNECTION_ID), ARBITRUM), DUMMY_CONNECTION_ID)
    }

    @Test
    fun hyperliquidUserSignedUsdSendReviewsEveryFieldAndShowsItsUnsignedMembersAsNotSigned() {
        val review = reviewed(USD_SEND, ARBITRUM)

        assertEquals("HyperliquidTransaction:UsdSend", review.title)
        noteOf(review, HYPERLIQUID_L1_LABEL)
        val message = review.sections.single { it.heading == "Message" }.rows.associate { it.label to it.value.text() }
        assertEquals(AGENT, message["destination"])
        assertEquals("1", message["amount"])
        assertEquals("1687816341423", message["time"])
        assertEquals(
            listOf("type" to "usdSend", "signatureChainId" to "0x66eee"),
            review.sections.single { it.heading == "Not Signed" }.rows.map { it.label to it.value.text() },
        )
        assertNoStoppingWarning(review)
    }

    @Test
    fun hyperliquidWithdrawAndApproveAgentReviewWithTheirUnsignedMembersShown() {
        val withdraw = userSigned(
            "HyperliquidTransaction:Withdraw",
            TRANSFER_FIELDS,
            """"type":"withdraw3","destination":"$AGENT","amount":"1","time":1687816341423,"signatureChainId":"0x66eee",""" +
                """"hyperliquidChain":"Testnet"""",
        )
        assertNoStoppingWarning(reviewed(withdraw, ARBITRUM))
        val approve = userSigned(
            "HyperliquidTransaction:ApproveAgent",
            """{"name":"hyperliquidChain","type":"string"},{"name":"agentAddress","type":"address"},""" +
                """{"name":"agentName","type":"string"},{"name":"nonce","type":"uint64"}""",
            """"type":"approveAgent","agentAddress":"$AGENT","agentName":"example","nonce":1700000000000,""" +
                """"signatureChainId":"0x66eee","hyperliquidChain":"Mainnet"""",
        )
        val review = reviewed(approve, ARBITRUM)

        assertEquals("HyperliquidTransaction:ApproveAgent", review.title)
        assertEquals(AGENT, review.sections.single { it.heading == "Message" }.rows.single { it.label == "agentAddress" }.value.text())
        assertEquals("example", review.sections.single { it.heading == "Message" }.rows.single { it.label == "agentName" }.value.text())
    }

    @Test
    fun asterDexApprovalOnBnbShowsTheWholeMessageText() {
        val review = reviewed(asterMessage(56, ASTER_APPROVE_MESSAGE), BNB)

        assertEquals("Message", review.title)
        assertEquals(ASTER_LABEL, noteOf(review, ASTER_LABEL).label)
        assertShownWhole(review, ASTER_APPROVE_MESSAGE)
        assertNoStoppingWarning(review)
    }

    @Test
    fun asterDexMasterAccountSignatureOnItsOwnDomainShowsTheWholeMessageText() {
        val review = reviewed(asterMessage(1666, ASTER_MASTER_MESSAGE), BNB)

        assertShownWhole(review, ASTER_MASTER_MESSAGE)
        assertTrue(rowsOf(review).any { it.value.text() == "1666 (off-chain domain, not a network)" })
        assertNoStoppingWarning(review)
    }

    @Test
    fun lighterApiKeyMessageIsAPersonalMessageThatShowsEveryLineOfItsText() {
        val review = assertIs<EvmReviewResult.Ok>(reviewOf(LIGHTER_MESSAGE.toByteArray(Charsets.UTF_8), null, DataType.PERSONAL_MESSAGE)).review

        assertEquals("Sign Message", review.title)
        assertShownWhole(review, LIGHTER_MESSAGE)
        assertTrue(rowsOf(review).any { it.value is DisplayValue.Message && it.value.text() == LIGHTER_MESSAGE })
    }

    @Test
    fun aDomainOnAnotherChainThatIsNotDeclaredIsStillRefused() {
        val reason = "The typed data is for chain 1337, but the request names chain 42161"

        // The same name, version and chain, with another verifying contract.
        val otherContract = l1Agent("a", DUMMY_CONNECTION_ID).replace(ZERO, "0x1111111111111111111111111111111111111111")
        assertEquals(reason, refusedReason(otherContract, ARBITRUM))
        // The same chain and contract, with another name.
        val otherName = l1Agent("a", DUMMY_CONNECTION_ID).replace("\"Exchange\"", "\"Exchange2\"")
        assertEquals(reason, refusedReason(otherName, ARBITRUM))
        // A declared domain with another version.
        val otherVersion = userSigned("HyperliquidTransaction:UsdSend", TRANSFER_FIELDS, "\"destination\":\"$AGENT\"", version = "2")
        assertEquals("The typed data is for chain 421614, but the request names chain 42161", refusedReason(otherVersion, ARBITRUM))
        // An ordinary Permit on Ethereum, asked for on Arbitrum.
        val permit = asterMessage(1, ASTER_APPROVE_MESSAGE).replace("AsterSignTransaction", "USD Coin")
        assertEquals("The typed data is for chain 1, but the request names chain 42161", refusedReason(permit, ARBITRUM))
    }

    @Test
    fun aMemberThatTheAllowlistDoesNotNameIsStillRefused() {
        // Only the user-signed domain allows type and signatureChainId. The L1 domain allows no extra member.
        val extraOnL1 = l1Agent("a", DUMMY_CONNECTION_ID, extra = ""","type":"dummy"""")
        assertEquals("message has an unknown member type", refusedReason(extraOnL1, ARBITRUM))
        val unknownExtra = USD_SEND.replace("\"hyperliquidChain\":\"Testnet\"", "\"hyperliquidChain\":\"Testnet\",\"memo\":\"hi\"")
        assertEquals("message has an unknown member memo", refusedReason(unknownExtra, ARBITRUM))
    }
}
