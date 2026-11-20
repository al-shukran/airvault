package app.airvault.chains.evm.signer

import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.Warning
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
private const val AGENT_CHECKSUM = "0x5E9EE1089755C3435139848e47e6635505D5a13A"
private const val DUMMY_CONNECTION_ID = "0xf528daee6a0bd11407b483cfcd9a48c56884180b70ee86f124053e5fc1bf4d57"
private const val PRODUCTION_CONNECTION_ID = "0x0fcbeda5ae3c4950a548021552a4fea2226858c4453571bf3f24ba017eac2908"
private const val ARBITRUM = 42161L
private const val BNB = 56L
private const val HYPERLIQUID_L1_LABEL = "Off-Chain Signature for Hyperliquid"
private const val ASTER_LABEL = "Off-Chain Signature for AsterDex"
private const val HL_NOTE = "The domain matches Hyperliquid's published message format. " +
    "Lensly cannot tell what the requester does with the signature."
private const val ASTER_NOTE = "The domain matches AsterDex's published message format. " +
    "Lensly cannot tell what the requester does with the signature."
private const val HASHED_LABEL = "Order Details Are Hashed by Hyperliquid and Cannot Be Shown"
private const val HASHED_NOTE = "The connection ID is a hash of the action. Lensly cannot read the action, so it cannot show what the action does."
private const val HL_CAUTION = "This signature is for Hyperliquid. It can authorize actions in your Hyperliquid account. " +
    "Check the message before you sign."
private const val ASTER_CAUTION = "This signature is for AsterDex. It can authorize actions in your AsterDex account. " +
    "Check the message before you sign."
private const val HL_ACK = "I understand this signature goes to Hyperliquid, and Lensly cannot tell what it does."
private const val ANY_CHAIN_NOTE = "The domain names no chain ID, so this signature is valid on any chain."
private const val APPROVE_TEXT = "This approves another key to act for this account. Check the agent address and name before you sign."
private const val WITHDRAW_TEXT = "This withdraws funds from this account. Check the destination and amount before you sign."

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

/** The CAUTION warnings of [review] that a tick acknowledges. */
private fun cautionsOf(review: Review): List<Warning> = review.warnings.filter { it.level == Danger.CAUTION && it.acknowledge != null }

/** The DANGER warnings of [review] that a tick acknowledges. */
private fun dangersOf(review: Review): List<Warning> = review.warnings.filter { it.level == Danger.DANGER && it.acknowledge != null }

/**
 * The CAUTION that a request on a chain outside Lensly's registry gets. The Hyperliquid chains are outside it, so the reviews of a request
 * on one of them show it beside the CAUTIONs of the signature.
 */
private const val UNKNOWN_NETWORK_CAUTION = "This network is not in Lensly's registry, so Lensly cannot check its coin or decimals."

/** The texts of the CAUTIONs that a tick acknowledges, without the registry's CAUTION for a chain outside the registry. */
private fun cautionTexts(review: Review): List<String> = cautionsOf(review).map { it.message }.filterNot { it == UNKNOWN_NETWORK_CAUTION }

/** The CAUTION of a signature valid on [chain], under a request that names [requested] (a chain ID, or no chain ID). */
private fun chainCaution(chain: String, name: String, requested: String): String =
    "The signature is valid on chain ID $chain ($name), and the request names $requested. Check that chain before you sign."

/** Asserts that a row shows [expected] whole, and that no row abbreviates a value with an ellipsis. */
private fun assertShownWhole(review: Review, expected: String) {
    val texts = rowsOf(review).map { it.value.text() }
    assertTrue(expected in texts, "no row shows the full text")
    assertFalse(texts.any { "…" in it || "..." in it }, "a row is abbreviated with an ellipsis")
}

private fun assertNoStoppingWarning(review: Review) {
    assertTrue(review.warnings.none { it.stopsSigning }, "the review has a warning that stops signing: ${review.warnings}")
}

/** The note row named [label], which must show [text]. */
private fun noteOf(review: Review, label: String, text: String): ReviewRow {
    val row = assertNotNull(rowsOf(review).singleOrNull { it.label == label }, "no note row named $label")
    assertEquals(text, row.value.text(), "the note row $label shows other text")
    return row
}

class OffChainSignatureReviewTest {

    @Test
    fun hyperliquidL1ActionReviewsOnItsOwnDomainWithTheWholeConnectionId() {
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID), ARBITRUM)

        assertEquals("Agent", review.title)
        noteOf(review, HYPERLIQUID_L1_LABEL, HL_NOTE)
        noteOf(review, HASHED_LABEL, HASHED_NOTE)
        assertShownWhole(review, DUMMY_CONNECTION_ID)
        assertTrue(rowsOf(review).any { it.value.text() == "1337 (Unknown Network)" })
        // The chain and the product each get a CAUTION with its tick. Neither stops the signature.
        assertEquals(
            listOf(chainCaution("1337", "Unknown Network", "chain ID $ARBITRUM"), HL_CAUTION),
            cautionTexts(review),
        )
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
        noteOf(review, HYPERLIQUID_L1_LABEL, HL_NOTE)
        val message = review.sections.single { it.heading == "Message" }.rows.associate { it.label to it.value.text() }
        assertEquals(AGENT, message["destination (string)"])
        assertEquals("1", message["amount (string)"])
        assertEquals("1687816341423", message["time (uint64)"])
        assertEquals(
            listOf("Unsigned: type" to "usdSend", "Unsigned: signatureChainId" to "0x66eee"),
            review.sections.single { it.heading == "Not Signed" }.rows.map { it.label to it.value.text() },
        )
        assertEquals(
            listOf(chainCaution("421614", "Unknown Network", "chain ID $ARBITRUM"), HL_CAUTION),
            cautionTexts(review),
        )
        assertTrue(dangersOf(review).isEmpty(), "a usdSend is not a DANGER")
        assertNoStoppingWarning(review)
    }

    @Test
    fun hyperliquidWithdrawAndApproveAgentReviewWithTheirUnsignedMembersShownAndADangerTick() {
        val withdraw = userSigned(
            "HyperliquidTransaction:Withdraw",
            TRANSFER_FIELDS,
            """"type":"withdraw3","destination":"$AGENT","amount":"1","time":1687816341423,"signatureChainId":"0x66eee",""" +
                """"hyperliquidChain":"Testnet"""",
        )
        val withdrawReview = reviewed(withdraw, ARBITRUM)
        assertNoStoppingWarning(withdrawReview)
        assertEquals(listOf(WITHDRAW_TEXT), dangersOf(withdrawReview).map { it.message })
        assertEquals(listOf("I understand this withdraws funds from this account."), dangersOf(withdrawReview).map { it.acknowledge })

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
        assertEquals(AGENT_CHECKSUM, message["agentAddress (address)"])
        assertEquals("example", message["agentName (string)"])
        assertEquals(listOf(APPROVE_TEXT), dangersOf(review).map { it.message })
        assertEquals(listOf("I understand this lets another key act for this account."), dangersOf(review).map { it.acknowledge })
        assertNoStoppingWarning(review)
    }

    @Test
    fun asterDexApprovalOnBnbShowsTheWholeMessageText() {
        val review = reviewed(asterMessage(56, ASTER_APPROVE_MESSAGE), BNB)

        assertEquals("Message", review.title)
        noteOf(review, ASTER_LABEL, ASTER_NOTE)
        assertShownWhole(review, ASTER_APPROVE_MESSAGE)
        // The request names the domain's chain, so the only CAUTION is the product's.
        assertEquals(listOf(ASTER_CAUTION), cautionTexts(review))
        assertNoStoppingWarning(review)
    }

    @Test
    fun anAsterDexAgentApprovalIsADangerWithATick() {
        val review = reviewed(asterMessage(56, ASTER_APPROVE_MESSAGE), BNB)

        assertEquals(listOf(APPROVE_TEXT), dangersOf(review).map { it.message })
    }

    @Test
    fun anAsterDexMessageWithoutTheAgentOnChain56IsNotAnAgentApprovalAndShowsItsWholeText() {
        // The domain is the approval's, but a message that names no agentAddress parameter is not an approval.
        for (msg in listOf("hello", ASTER_MASTER_MESSAGE)) {
            val review = reviewed(asterMessage(56, msg), BNB)

            assertShownWhole(review, msg)
            assertTrue(dangersOf(review).isEmpty(), "a message without the agent is a DANGER: $msg")
            assertNoStoppingWarning(review)
        }
    }

    @Test
    fun asterDexMasterAccountSignatureOnItsOwnDomainShowsTheWholeMessageText() {
        val review = reviewed(asterMessage(1666, ASTER_MASTER_MESSAGE), BNB)

        assertShownWhole(review, ASTER_MASTER_MESSAGE)
        noteOf(review, ASTER_LABEL, ASTER_NOTE)
        assertTrue(rowsOf(review).any { it.value.text() == "1666 (Unknown Network)" })
        assertEquals(listOf(chainCaution("1666", "Unknown Network", "chain ID $BNB"), ASTER_CAUTION), cautionTexts(review))
        assertTrue(dangersOf(review).isEmpty(), "a sub-account signature is not an agent approval")
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
        assertTrue(rowsOf(unlabelled).none { it.label == HYPERLIQUID_L1_LABEL })
        assertEquals(listOf(chainCaution("1337", "Unknown Network", "chain ID $ARBITRUM")), cautionTexts(unlabelled))
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
        // The chain CAUTION. The token is not in the test registry, so its own CAUTION is shown as well.
        assertTrue(cautionsOf(review).any { it.message == chainCaution("1", "Ethereum", "chain ID $ARBITRUM") })
        assertNoStoppingWarning(review)
    }

    @Test
    fun typedDataWithNoRequestChainIsReadOnTheChainItsDomainNames() {
        // Rabby names no chain in a typed-data request, so the domain's chain is the chain the review reads.
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID), null)

        noteOf(review, HYPERLIQUID_L1_LABEL, HL_NOTE)
        assertTrue(rowsOf(review).any { it.label == "Signature for Chain 1337 (Unknown Network)" })
        assertShownWhole(review, DUMMY_CONNECTION_ID)
        // The request names no chain, so the chain the domain names is absent from it: that is a CAUTION too.
        assertEquals(
            listOf(chainCaution("1337", "Unknown Network", "no chain ID"), HL_CAUTION),
            cautionTexts(review),
        )
        assertNoStoppingWarning(review)
    }

    @Test
    fun aLabelOnTheRequestsOwnChainIsOnlyCautionWithATick() {
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID), 1337L)

        noteOf(review, HYPERLIQUID_L1_LABEL, HL_NOTE)
        assertEquals(listOf(HL_CAUTION), cautionTexts(review))
        assertEquals(listOf(HL_ACK), cautionsOf(review).filter { it.message == HL_CAUTION }.map { it.acknowledge })
        assertNoStoppingWarning(review)
    }

    @Test
    fun aPrimaryTypeOutsideTheProductsListIsNotLabelled() {
        // The same domain as the L1 action, with the primary type renamed. The product signs only Agent on this domain.
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID).replace("Agent", "Other"), 1337L)

        assertTrue(rowsOf(review).none { it.label == HYPERLIQUID_L1_LABEL || it.label == HASHED_LABEL })
        assertTrue(cautionTexts(review).isEmpty(), "an unlabelled signature on the request's chain has no CAUTION")
        assertNoStoppingWarning(review)
    }

    @Test
    fun aDomainThatNamesNoChainIsSignableUnderAnyRequestAndSaysSo() {
        val json = """{"types":{"Note":[{"name":"text","type":"string"}],"EIP712Domain":[{"name":"name","type":"string"}]},""" +
            """"primaryType":"Note","domain":{"name":"Lensly"},"message":{"text":"hi"}}"""

        for (chain in listOf(ARBITRUM, null)) {
            val review = reviewed(json, chain)
            assertTrue(rowsOf(review).any { it.label == "Signature for Any Chain" })
            assertEquals(listOf(ANY_CHAIN_NOTE), cautionTexts(review))
            assertNoStoppingWarning(review)
        }
    }

    @Test
    fun aWithdrawPrimaryTypeIsDangerWithATickEvenOnAnUnlabelledDomain() {
        val json = """{"types":{"Withdraw":[{"name":"amount","type":"uint256"}],"EIP712Domain":[$DOMAIN_TYPES]},"primaryType":"Withdraw",""" +
            """"domain":{"name":"Lensly","version":"1","chainId":$ARBITRUM,"verifyingContract":"$ZERO"},"message":{"amount":"1"}}"""

        val review = reviewed(json, ARBITRUM)

        assertEquals(listOf(WITHDRAW_TEXT), dangersOf(review).map { it.message })
        assertNoStoppingWarning(review)
    }

    @Test
    fun aDeclaredSaltIsShownButTheLabelNeedsExactlyTheFourSdkMembers() {
        val salt = "0x" + "11".repeat(32)
        val json = """{"types":{"Agent":[{"name":"source","type":"string"},{"name":"connectionId","type":"bytes32"}],""" +
            """"EIP712Domain":[$DOMAIN_TYPES,{"name":"salt","type":"bytes32"}]},"primaryType":"Agent",""" +
            """"domain":{"chainId":1337,"name":"Exchange","verifyingContract":"$ZERO","version":"1","salt":"$salt"},""" +
            """"message":{"source":"a","connectionId":"$DUMMY_CONNECTION_ID"}}"""

        val review = reviewed(json, 1337L)

        assertTrue(rowsOf(review).any { it.label == "Salt" && it.value.text() == salt }, "the declared salt is shown")
        assertTrue(rowsOf(review).none { it.label == HYPERLIQUID_L1_LABEL || it.label == HASHED_LABEL }, "a domain with a salt has no label")
        assertTrue(cautionTexts(review).isEmpty())
    }

    @Test
    fun aDomainWithAnotherVersionIsNotLabelled() {
        val review = reviewed(l1Agent("a", DUMMY_CONNECTION_ID).replace("\"version\":\"1\"", "\"version\":\"2\""), 1337L)

        assertTrue(rowsOf(review).none { it.label == HYPERLIQUID_L1_LABEL || it.label == HASHED_LABEL })
        assertTrue(cautionTexts(review).isEmpty())
    }

    @Test
    fun aSaltTheDomainTypeDoesNotDeclareIsRefused() {
        val json = l1Agent("a", DUMMY_CONNECTION_ID)
            .replace("\"domain\":{\"chainId\":1337,", "\"domain\":{\"chainId\":1337,\"salt\":\"0x${"11".repeat(32)}\",")

        assertEquals("domain has an unknown member salt", refusedReason(json, 1337L))
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
        assertTrue(cautionTexts(review).isEmpty(), "the domain names the request's chain, so there is nothing to caution")
        assertNoStoppingWarning(review)
    }

    @Test
    fun anUndeclaredScalarMemberIsShownAsNotSignedOnAnyDomainButANestedOneIsStillRefused() {
        // The L1 domain names no member beyond its type. A scalar the message adds is shown under Not Signed, as on any domain.
        val extraOnL1 = l1Agent("a", DUMMY_CONNECTION_ID, extra = ""","type":"dummy"""")
        val notSignedOnL1 = reviewed(extraOnL1, ARBITRUM).sections.single { it.heading == "Not Signed" }.rows
        assertEquals(listOf("Unsigned: type" to "dummy"), notSignedOnL1.map { it.label to it.value.text() })
        // The same on a domain the registry does not label.
        val unlabelled = asterMessage(1, ASTER_APPROVE_MESSAGE).replace("AsterSignTransaction", "USD Coin")
            .replace("\"message\":{\"msg\"", "\"message\":{\"memo\":\"hi\",\"msg\"")
        val notSignedOnPermit = reviewed(unlabelled, ARBITRUM).sections.single { it.heading == "Not Signed" }.rows
        assertEquals(listOf("Unsigned: memo" to "hi"), notSignedOnPermit.map { it.label to it.value.text() })
        // A nested object the message adds is still refused.
        val nested = USD_SEND.replace("\"hyperliquidChain\":\"Testnet\"", "\"hyperliquidChain\":\"Testnet\",\"memo\":{\"a\":\"b\"}")
        assertEquals("message has an unknown member memo", refusedReason(nested, ARBITRUM))
    }

    @Test
    fun aNotSignedValueOrNameWithABidiOverrideIsShownInHexNotAsText() {
        // U+202E, the right-to-left override, reorders the text on screen. Its UTF-8 bytes are e2 80 ae.
        val json = USD_SEND.replace(
            "\"hyperliquidChain\":\"Testnet\"",
            "\"hyperliquidChain\":\"Testnet\",\"memo\":\"‮evil\",\"‮key\":\"x\"",
        )

        val notSigned = reviewed(json, ARBITRUM).sections.single { it.heading == "Not Signed" }.rows.map { it.label to it.value.text() }

        assertEquals(
            listOf(
                "Unsigned: type" to "usdSend",
                "Unsigned: signatureChainId" to "0x66eee",
                "Unsigned: memo" to "0xe280ae6576696c (not printable)",
                "Unsigned: 0xe280ae6b6579 (not printable)" to "x",
            ),
            notSigned,
        )
    }

    @Test
    fun aNotSignedLineBreakCannotStartARowAndALongNameOrValueIsCapped() {
        // A line break in an unsigned value is shown as an escape, so it cannot start a row. A name is capped at 128 bytes and a value at 512.
        val name = "n".repeat(200)
        val value = "1\\n\\tToken: fake" + "v".repeat(600)
        val json = USD_SEND.replace("\"hyperliquidChain\":\"Testnet\"", "\"hyperliquidChain\":\"Testnet\",\"$name\":\"$value\"")

        val notSigned = reviewed(json, ARBITRUM).sections.single { it.heading == "Not Signed" }.rows.map { it.label to it.value.text() }

        // The escaped value is 16 bytes of text and 600 more: 512 bytes are kept, and 104 are not.
        assertEquals(
            listOf(
                "Unsigned: type" to "usdSend",
                "Unsigned: signatureChainId" to "0x66eee",
                "Unsigned: ${"n".repeat(128)}... 72 more bytes" to "1\\n\\tToken: fake${"v".repeat(496)}... 104 more bytes",
            ),
            notSigned,
        )
    }
}
