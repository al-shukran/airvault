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

private const val AGENT_CHECKSUM = "0x5E9EE1089755C3435139848e47e6635505D5a13A"
private const val USDC_MAINNET = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

/** An EIP-2612 Permit of USD Coin on the domain of chain 1, which a request for Arbitrum asks to sign. The token is not in the test registry. */
private val PERMIT_ON_MAINNET = """{"types":{"EIP712Domain":[$DOMAIN_TYPES],"Permit":[{"name":"owner","type":"address"},""" +
    """{"name":"spender","type":"address"},{"name":"value","type":"uint256"},{"name":"nonce","type":"uint256"},""" +
    """{"name":"deadline","type":"uint256"}]},"primaryType":"Permit","domain":{"name":"USD Coin","version":"2","chainId":1,""" +
    """"verifyingContract":"$USDC_MAINNET"},"message":{"owner":"$SENDER","spender":"$AGENT","value":"1000000","nonce":"0",""" +
    """"deadline":"1800000000"}}"""

/** Long text of a nested message, long enough that a short display would cut it. */
private val MEMO = (1..60).joinToString(" ") { "memo$it" }
private val NOTE = (1..40).joinToString(" ") { "note$it" }
private val TERMS = (1..30).joinToString(" ") { "terms$it" }
private val REFERENCE = "0x" + "ab".repeat(32)

/** A message with a string, a list of two structs and a struct, one level deeper than its type names. */
private val NESTED_BATCH = """{"types":{"Batch":[{"name":"memo","type":"string"},{"name":"legs","type":"Leg[]"},""" +
    """{"name":"terms","type":"Terms"}],"Leg":[{"name":"asset","type":"address"},{"name":"note","type":"string"}],""" +
    """"Terms":[{"name":"reference","type":"bytes32"},{"name":"text","type":"string"}],"EIP712Domain":[$DOMAIN_TYPES]},""" +
    """"primaryType":"Batch","domain":{"name":"Lensly Batch","version":"1","chainId":$ARBITRUM,"verifyingContract":"$ZERO"},""" +
    """"message":{"memo":"$MEMO","legs":[{"asset":"$SENDER","note":"$NOTE"},{"asset":"$AGENT","note":"short"}],""" +
    """"terms":{"reference":"$REFERENCE","text":"$TERMS"}}}"""

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
    assertNotNull(rowsOf(review).singleOrNull { it.label == label }, "no note row named $label")

class OffChainSignatureReviewTest {

    @Test
    fun hyperliquidL1ActionReviewsOnItsOwnDomainWithTheWholeConnectionId() {
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID), ARBITRUM)

        assertEquals("Agent", review.title)
        assertEquals(HYPERLIQUID_L1_LABEL, noteOf(review, HYPERLIQUID_L1_LABEL).label)
        assertShownWhole(review, DUMMY_CONNECTION_ID)
        assertTrue(rowsOf(review).any { it.value.text() == "1337 (Unknown Network)" })
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
        assertEquals(AGENT, message["destination (string)"])
        assertEquals("1", message["amount (string)"])
        assertEquals("1687816341423", message["time (uint64)"])
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
        // An address field is shown in its checksum case, and each field's label names its type.
        val message = review.sections.single { it.heading == "Message" }.rows.associate { it.label to it.value.text() }
        assertTrue(message.getValue("agentAddress (address)").equals(AGENT, ignoreCase = true))
        assertEquals("example", message["agentName (string)"])
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
        assertTrue(rowsOf(review).any { it.value.text() == "1666 (Unknown Network)" })
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
    fun aDomainOnAnotherChainIsSignableAndItsRowNamesThatChain() {
        // A Hyperliquid domain on chain 1337 under a request for Arbitrum, with another verifying contract so no label applies.
        val otherContract = l1Agent("a", DUMMY_CONNECTION_ID).replace(ZERO, "0x1111111111111111111111111111111111111111")
        val unlabelled = reviewed(otherContract, ARBITRUM)
        assertTrue(rowsOf(unlabelled).any { it.label == "Signature for Chain 1337 (Unknown Network)" })
        assertNoStoppingWarning(unlabelled)
    }

    @Test
    fun aPermitOnAnotherChainThanTheRequestIsSignableAndShowsItsChainRowAndTheWholeMessage() {
        val review = reviewed(PERMIT_ON_MAINNET, ARBITRUM)

        assertEquals("Permit", review.title)
        val chainRow = assertNotNull(rowsOf(review).singleOrNull { it.label == "Signature for Chain 1 (Ethereum)" })
        assertEquals("The domain names chain ID 1, and the request names chain ID $ARBITRUM.", chainRow.value.text())
        assertEquals(
            mapOf(
                "owner (address)" to SENDER,
                "spender (address)" to AGENT_CHECKSUM,
                "value (uint256)" to "1000000",
                "nonce (uint256)" to "0",
                "deadline (uint256)" to "1800000000",
            ),
            review.sections.single { it.heading == "Message" }.rows.associate { it.label to it.value.text() },
        )
        assertNoStoppingWarning(review)
    }

    @Test
    fun typedDataWithNoRequestChainIsReadOnTheChainItsDomainNames() {
        // Rabby names no chain in a typed-data request, so the domain's chain is the chain the review reads.
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID), null)

        assertEquals(HYPERLIQUID_L1_LABEL, noteOf(review, HYPERLIQUID_L1_LABEL).label)
        assertTrue(rowsOf(review).any { it.label == "Signature for Chain 1337 (Unknown Network)" })
        assertShownWhole(review, DUMMY_CONNECTION_ID)
        assertNoStoppingWarning(review)
    }

    @Test
    fun aDomainThatNamesNoChainIsSignableUnderAnyRequestAndSaysSo() {
        val json = """{"types":{"Note":[{"name":"text","type":"string"}],"EIP712Domain":[{"name":"name","type":"string"}]},""" +
            """"primaryType":"Note","domain":{"name":"Lensly"},"message":{"text":"hi"}}"""

        for (chain in listOf(ARBITRUM, null)) {
            val review = reviewed(json, chain)
            assertTrue(rowsOf(review).any { it.label == "Signature for Any Chain" })
            assertNoStoppingWarning(review)
        }
    }

    @Test
    fun aTypeNameWithAColonIsRefusedOnADomainThatHasNoLabel() {
        // The usdSend action on a domain no label names. Only a labelled off-chain domain may name its types with a colon.
        val unlabelled = USD_SEND.replace("HyperliquidSignTransaction", "Other Signer")

        assertEquals("a type name with a colon is read only in a labelled off-chain domain", refusedReason(unlabelled, ARBITRUM))
    }

    @Test
    fun aNestedMessageShowsEveryFieldWholeAndInItsOrder() {
        val review = reviewed(NESTED_BATCH, ARBITRUM)

        val rows = review.sections.single { it.heading == "Message" }.rows
        val leaves = rows.mapNotNull { row -> row.value?.let { row.label to it.text() } }
        assertEquals(
            listOf(
                "memo (string)" to MEMO,
                "asset (address)" to SENDER,
                "note (string)" to NOTE,
                "asset (address)" to AGENT_CHECKSUM,
                "note (string)" to "short",
                "reference (bytes32)" to REFERENCE,
                "text (string)" to TERMS,
            ),
            leaves,
        )
        assertShownWhole(review, NOTE)
        assertNoStoppingWarning(review)
    }

    @Test
    fun anUndeclaredScalarMemberIsShownAsNotSignedOnAnyDomainButANestedOneIsStillRefused() {
        // The L1 domain names no member beyond its type. A scalar the message adds is shown under Not Signed, as on any domain.
        val extraOnL1 = l1Agent("a", DUMMY_CONNECTION_ID, extra = ""","type":"dummy"""")
        val notSignedOnL1 = reviewed(extraOnL1, ARBITRUM).sections.single { it.heading == "Not Signed" }.rows
        assertEquals(listOf("type" to "dummy"), notSignedOnL1.map { it.label to it.value.text() })
        // The same on a domain the registry does not label.
        val unlabelled = asterMessage(1, ASTER_APPROVE_MESSAGE).replace("AsterSignTransaction", "USD Coin")
            .replace("\"message\":{\"msg\"", "\"message\":{\"memo\":\"hi\",\"msg\"")
        val notSignedOnPermit = reviewed(unlabelled, ARBITRUM).sections.single { it.heading == "Not Signed" }.rows
        assertEquals(listOf("memo" to "hi"), notSignedOnPermit.map { it.label to it.value.text() })
        // A nested object the message adds is still refused.
        val nested = USD_SEND.replace("\"hyperliquidChain\":\"Testnet\"", "\"hyperliquidChain\":\"Testnet\",\"memo\":{\"a\":\"b\"}")
        assertEquals("message has an unknown member memo", refusedReason(nested, ARBITRUM))
    }
}
