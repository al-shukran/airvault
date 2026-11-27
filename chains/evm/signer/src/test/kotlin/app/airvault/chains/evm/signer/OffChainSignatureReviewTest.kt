package app.airvault.chains.evm.signer

import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.Warning
import app.airvault.core.model.stopsSigning
import app.airvault.core.model.text
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.NetworkKind
import app.lensly.core.registry.Registry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.web3j.crypto.Keys

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
private const val HASHED_LABEL = "Action Details Are Hashed by Hyperliquid and Cannot Be Shown"
private const val HASHED_NOTE = "This message carries a hash of the action. Lensly cannot read the action, so it cannot show what it does. " +
    "It can be an order, a transfer into a vault, a transfer between sub-accounts, or another action."
private const val MULTI_SIG_TEXT = "This hands control of this account to the listed signers. Your own key can no longer act alone. " +
    "Check the signers and the threshold before you sign."
private const val HL_CAUTION = "This signature is for Hyperliquid. It can authorize actions in your Hyperliquid account. " +
    "Check the message before you sign."
private const val ASTER_CAUTION = "This signature is for AsterDex. It can authorize actions in your AsterDex account. " +
    "Check the message before you sign."
private const val HL_ACK = "I understand this signature goes to Hyperliquid, and Lensly cannot tell what it does."
private const val ANY_CHAIN_NOTE = "The domain names no chain ID, so this signature is valid on any chain."
private const val APPROVE_TEXT = "This approves another key to act for this account. Check the agent address and name before you sign."
private const val BUILDER_FEE_TEXT = "This lets the builder take a fee of up to the maximum fee rate on every order you place. " +
    "Check the builder address and the maximum fee rate before you sign."
private const val TOKEN_DELEGATE_TEXT = "This stakes HYPE with a validator, or takes it back from one. Staked HYPE is locked for a while. " +
    "Check the validator, the amount and whether it undelegates before you sign."
private const val SUB_ACCOUNT_TEXT = "This sends from a sub-account, not from the account that signs. Check the sub-account address before you sign."
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

private const val HYPE_SYSTEM = "0x2222222222222222222222222222222222222222"
private const val TOKEN_SYSTEM = "0x20000000000000000000000000000000000000c8"
private const val HYPE_NEAR_MISS = "0x2222222222222222222222222222222222222223"
private const val TOKEN_NEAR_MISS = "0x21000000000000000000000000000000000000c8"

private const val SPOT_SEND_FIELDS = """{"name":"hyperliquidChain","type":"string"},{"name":"destination","type":"string"},""" +
    """{"name":"token","type":"string"},{"name":"amount","type":"string"},{"name":"time","type":"uint64"}"""

private const val SEND_ASSET_FIELDS = """{"name":"hyperliquidChain","type":"string"},{"name":"destination","type":"string"},""" +
    """{"name":"sourceDex","type":"string"},{"name":"destinationDex","type":"string"},{"name":"token","type":"string"},""" +
    """{"name":"amount","type":"string"},{"name":"fromSubAccount","type":"string"},{"name":"nonce","type":"uint64"}"""

/** A SpotSend of HYPE to [destination] on the HyperliquidSignTransaction domain, as the SDK builds it. */
private fun spotSend(destination: String) = userSigned(
    "HyperliquidTransaction:SpotSend",
    SPOT_SEND_FIELDS,
    """"type":"spotSend","destination":"$destination","token":"HYPE:0x0d01dc56dcaaca66ad901c959b4011ec","amount":"1",""" +
        """"time":1687816341423,"signatureChainId":"0x66eee","hyperliquidChain":"Mainnet"""",
)

/** A SendAsset of HYPE to [destination] on the HyperliquidSignTransaction domain, as the SDK builds it. */
private fun sendAsset(destination: String) = userSigned(
    "HyperliquidTransaction:SendAsset",
    SEND_ASSET_FIELDS,
    """"type":"sendAsset","destination":"$destination","sourceDex":"spot","destinationDex":"","token":"HYPE:0x0d01dc56dcaaca66ad901c959b4011ec",""" +
        """"amount":"1","fromSubAccount":"","nonce":1687816341423,"signatureChainId":"0x66eee","hyperliquidChain":"Mainnet"""",
)

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
        NetworkEntry(1, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
        NetworkEntry(BNB, "BNB Smart Chain", "BNB", 18, FeeModel.L1, "BscScan", testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
        NetworkEntry(ARBITRUM, "Arbitrum One", "ETH", 18, FeeModel.L1, "Arbiscan", testnet = false, featured = false, kind = NetworkKind.LAYER_1, parentChainId = null),
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
    fun theHyperliquidNetworkIsNamedFromSourceAndFromHyperliquidChain() {
        val mainnet = reviewed(l1Agent("a", DUMMY_CONNECTION_ID), ARBITRUM)
        val mainnetRow = assertNotNull(rowsOf(mainnet).singleOrNull { it.label == "Network" })
        assertEquals("Hyperliquid Mainnet", mainnetRow.value.text())
        assertTrue(mainnetRow.notes.isNotEmpty(), "the mainnet row says it holds real funds")
        assertEquals("Hyperliquid Testnet", rowsOf(reviewed(l1Agent("b", DUMMY_CONNECTION_ID), ARBITRUM)).single { it.label == "Network" }.value.text())
        // A user-signed action names its network in hyperliquidChain.
        assertEquals("Hyperliquid Testnet", rowsOf(reviewed(USD_SEND, ARBITRUM)).single { it.label == "Network" }.value.text())
        assertEquals("Hyperliquid Mainnet", rowsOf(reviewed(spotSend(AGENT), ARBITRUM)).single { it.label == "Network" }.value.text())
        // A source the SDK does not write names no network.
        assertTrue(rowsOf(reviewed(l1Agent("c", DUMMY_CONNECTION_ID), ARBITRUM)).none { it.label == "Network" })
    }

    @Test
    fun aSendMultiSigMessageShowsTheHashedActionNoteAndAUsdSendDoesNot() {
        val multiSig = userSigned(
            "HyperliquidTransaction:SendMultiSig",
            """{"name":"hyperliquidChain","type":"string"},{"name":"multiSigActionHash","type":"bytes32"},{"name":"nonce","type":"uint64"}""",
            """"hyperliquidChain":"Mainnet","multiSigActionHash":"$DUMMY_CONNECTION_ID","nonce":1687816341423,""" +
                """"type":"sendMultiSig","signatureChainId":"0x66eee"""",
        )
        val review = reviewed(multiSig, ARBITRUM)

        noteOf(review, HASHED_LABEL, HASHED_NOTE)
        assertShownWhole(review, DUMMY_CONNECTION_ID)
        assertTrue(rowsOf(reviewed(USD_SEND, ARBITRUM)).none { it.label == HASHED_LABEL })
    }

    @Test
    fun hyperliquidConvertToMultiSigUserIsADangerWithATick() {
        val convert = userSigned(
            "HyperliquidTransaction:ConvertToMultiSigUser",
            """{"name":"hyperliquidChain","type":"string"},{"name":"signers","type":"string"},{"name":"nonce","type":"uint64"}""",
            """"hyperliquidChain":"Mainnet","signers":"{\"authorizedUsers\":[\"$AGENT\"],\"threshold\":1}","nonce":1687816341423,""" +
                """"type":"convertToMultiSigUser","signatureChainId":"0x66eee"""",
        )
        val review = reviewed(convert, ARBITRUM)

        assertEquals(listOf(MULTI_SIG_TEXT), dangersOf(review).map { it.message })
        assertEquals(listOf("I understand this hands control of this account to other keys."), dangersOf(review).map { it.acknowledge })
        assertNoStoppingWarning(review)
    }

    @Test
    fun hyperliquidApproveBuilderFeeIsADangerWithATickAndShowsTheBuilderAsAnAddress() {
        val approve = userSigned(
            "HyperliquidTransaction:ApproveBuilderFee",
            """{"name":"hyperliquidChain","type":"string"},{"name":"maxFeeRate","type":"string"},{"name":"builder","type":"string"},""" +
                """{"name":"nonce","type":"uint64"}""",
            """"hyperliquidChain":"Mainnet","maxFeeRate":"0.1%","builder":"$AGENT","nonce":1687816341423,""" +
                """"type":"approveBuilderFee","signatureChainId":"0x66eee"""",
        )
        val review = reviewed(approve, ARBITRUM)

        assertEquals(listOf(BUILDER_FEE_TEXT), dangersOf(review).map { it.message })
        assertEquals(listOf("I understand this lets another party take a fee on my orders."), dangersOf(review).map { it.acknowledge })
        assertNoStoppingWarning(review)
        val message = review.sections.single { it.heading == "Message" }.rows.associateBy { it.label }
        assertEquals("0.1%", message.getValue("maxFeeRate (string)").value.text())
        assertEquals(DisplayValue.Address(ChainId("eip155:421614"), AGENT_CHECKSUM), message.getValue("builder (string)").value)
    }

    @Test
    fun hyperliquidTokenDelegateIsADangerWithATickAndShowsTheValidatorAmountAndDirection() {
        val delegate = userSigned(
            "HyperliquidTransaction:TokenDelegate",
            """{"name":"hyperliquidChain","type":"string"},{"name":"validator","type":"address"},{"name":"wei","type":"uint64"},""" +
                """{"name":"isUndelegate","type":"bool"},{"name":"nonce","type":"uint64"}""",
            """"hyperliquidChain":"Mainnet","validator":"$AGENT","wei":100000000,"isUndelegate":true,"nonce":1687816341423,""" +
                """"type":"tokenDelegate","signatureChainId":"0x66eee"""",
        )
        val review = reviewed(delegate, ARBITRUM)

        assertEquals(listOf(TOKEN_DELEGATE_TEXT), dangersOf(review).map { it.message })
        assertEquals(listOf("I understand this moves HYPE to or from a validator."), dangersOf(review).map { it.acknowledge })
        assertNoStoppingWarning(review)
        val message = review.sections.single { it.heading == "Message" }.rows.associate { it.label to it.value.text() }
        assertEquals(AGENT_CHECKSUM, message["validator (address)"])
        assertEquals("100000000", message["wei (uint64)"])
        assertEquals("true", message["isUndelegate (bool)"])
    }

    @Test
    fun aSendAssetFromASubAccountShowsItAsAnAddressRowWithACautionAndAPlainSendAssetDoesNot() {
        val plain = reviewed(sendAsset(AGENT), ARBITRUM)
        val plainRow = plain.sections.single { it.heading == "Message" }.rows.single { it.label == "fromSubAccount (string)" }
        assertEquals("(empty)", plainRow.value.text())
        assertTrue(cautionsOf(plain).none { it.message == SUB_ACCOUNT_TEXT })

        val review = reviewed(sendAsset(AGENT).replace(""""fromSubAccount":""""", """"fromSubAccount":"$AGENT""""), ARBITRUM)
        val row = review.sections.single { it.heading == "Message" }.rows.single { it.label == "fromSubAccount (string)" }
        assertEquals(DisplayValue.Address(ChainId("eip155:421614"), AGENT_CHECKSUM), row.value)
        assertEquals(listOf(SUB_ACCOUNT_TEXT), cautionsOf(review).map { it.message }.filter { it == SUB_ACCOUNT_TEXT })
        assertTrue(cautionsOf(review).any { it.acknowledge == "I understand this sends from a sub-account." })
        assertNoStoppingWarning(review)
    }

    @Test
    fun aHyperliquidDestinationThatIsAnAddressIsShownAsAChecksummedAddressRow() {
        val withdraw = userSigned(
            "HyperliquidTransaction:Withdraw",
            TRANSFER_FIELDS,
            """"type":"withdraw3","destination":"$AGENT","amount":"1","time":1687816341423,"signatureChainId":"0x66eee",""" +
                """"hyperliquidChain":"Mainnet"""",
        )
        val row = reviewed(withdraw, ARBITRUM).sections.single { it.heading == "Message" }.rows.single { it.label == "destination (string)" }

        assertEquals(DisplayValue.Address(ChainId("eip155:421614"), AGENT_CHECKSUM), row.value)
        // Other string members stay text, and a destination that is not an address stays text.
        val notAddress = withdraw.replace(AGENT, "not an address")
        val other = reviewed(notAddress, ARBITRUM).sections.single { it.heading == "Message" }.rows.associateBy { it.label }
        assertEquals(DisplayValue.Text("not an address"), other.getValue("destination (string)").value)
        assertEquals(DisplayValue.Text("1"), other.getValue("amount (string)").value)
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
        assertEquals(AGENT_CHECKSUM, message["destination (string)"])
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
    fun anAsterDexMessageWhoseMsgIsNotTextIsAnAgentApprovalAndADanger() {
        // The msg is bytes, so the agent cannot be read from it. A msg that is not text counts as the approval, so it fails closed.
        val json = """{"types":{"EIP712Domain":[$DOMAIN_TYPES],"Message":[{"name":"msg","type":"bytes"}]},"primaryType":"Message",""" +
            """"domain":{"name":"AsterSignTransaction","version":"1","chainId":56,"verifyingContract":"$ZERO"},""" +
            """"message":{"msg":"0x${"ab".repeat(16)}"}}"""

        val review = reviewed(json, BNB)

        assertEquals(listOf(APPROVE_TEXT), dangersOf(review).map { it.message })
    }

    @Test
    fun anAsterDexMessageWithoutAMsgIsAnAgentApprovalAndADanger() {
        // A labelled Message type that declares no msg has no agent to read, so the message counts as the approval, failing closed.
        val json = """{"types":{"EIP712Domain":[$DOMAIN_TYPES],"Message":[{"name":"note","type":"string"}]},"primaryType":"Message",""" +
            """"domain":{"name":"AsterSignTransaction","version":"1","chainId":56,"verifyingContract":"$ZERO"},""" +
            """"message":{"note":"hi"}}"""

        val review = reviewed(json, BNB)

        assertEquals(listOf(APPROVE_TEXT), dangersOf(review).map { it.message })
    }

    @Test
    fun anAsterDexMasterAccountMsgOnChain56IsNotAnAgentApprovalAndShowsItsWholeText() {
        // The documented master-account msg, exactly, is not an approval.
        val review = reviewed(asterMessage(56, ASTER_MASTER_MESSAGE), BNB)

        assertShownWhole(review, ASTER_MASTER_MESSAGE)
        assertTrue(dangersOf(review).isEmpty(), "the documented master message is a DANGER")
        assertNoStoppingWarning(review)
    }

    @Test
    fun anAsterDexMsgThatIsNotTheDocumentedFormIsADangerAndShownWhole() {
        val spaced = ASTER_APPROVE_MESSAGE.replace("agentAddress", " agentAddress")
        val escaped = ASTER_APPROVE_MESSAGE.replace("agentAddress", "agent%41ddress")
        val reordered = "name=example&childAddress=$AGENT&nonce=1&user=$SENDER&childSignature=0xab"
        val trailing = "$ASTER_MASTER_MESSAGE "
        val escapedMaster = ASTER_MASTER_MESSAGE.replace("childAddress", "child%41ddress")
        for (msg in listOf("hello", spaced, escaped, reordered, trailing, escapedMaster, "$ASTER_MASTER_MESSAGE&agentAddress=$AGENT")) {
            val review = reviewed(asterMessage(56, msg), BNB)

            assertShownWhole(review, msg)
            assertEquals(listOf(APPROVE_TEXT), dangersOf(review).map { it.message }, "no DANGER for: $msg")
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
    fun hyperCoreSpotSendToHypeSystemAddressIsLabelledMoveToHyperEvm() {
        val review = reviewed(spotSend(HYPE_SYSTEM), ARBITRUM)

        assertEquals("Move to HyperEVM", review.title)
        noteOf(review, HYPERLIQUID_L1_LABEL, HL_NOTE)
        // The label adds to the review: every field of the message is still shown, and the signature still carries its CAUTION.
        val message = review.sections.single { it.heading == "Message" }.rows.associate { it.label to it.value.text() }
        assertEquals(HYPE_SYSTEM, message["destination (string)"])
        assertEquals("HYPE:0x0d01dc56dcaaca66ad901c959b4011ec", message["token (string)"])
        assertEquals("1", message["amount (string)"])
        assertTrue(HL_CAUTION in cautionTexts(review))
        assertNoStoppingWarning(review)
    }

    @Test
    fun hyperCoreSendAssetToATokenSystemAddressIsLabelledMoveToHyperEvm() {
        val review = reviewed(sendAsset(TOKEN_SYSTEM), ARBITRUM)

        assertEquals("Move to HyperEVM", review.title)
        val message = review.sections.single { it.heading == "Message" }.rows.associate { it.label to it.value.text() }
        assertEquals(Keys.toChecksumAddress(TOKEN_SYSTEM), message["destination (string)"])
        assertEquals("spot", message["sourceDex (string)"])
        assertTrue(HL_CAUTION in cautionTexts(review))
        assertNoStoppingWarning(review)
    }

    @Test
    fun aNearMissAddressOrAnotherHyperliquidActionIsNotLabelledAsAMoveToHyperEvm() {
        assertEquals("HyperliquidTransaction:SendAsset", reviewed(sendAsset(HYPE_NEAR_MISS), ARBITRUM).title)
        assertEquals("HyperliquidTransaction:SpotSend", reviewed(spotSend(TOKEN_NEAR_MISS), ARBITRUM).title)
        // A usdSend to a system address is not a move from HyperCore to HyperEVM.
        assertEquals("HyperliquidTransaction:UsdSend", reviewed(USD_SEND.replace(AGENT, HYPE_SYSTEM), ARBITRUM).title)
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

    /** A typed message of [primaryType] with one string member on the AsterSignTransaction domain of chain 56. */
    private fun asterOther(primaryType: String, chainId: Int = 56) =
        """{"types":{"EIP712Domain":[$DOMAIN_TYPES],"$primaryType":[{"name":"agent","type":"address"}]},"primaryType":"$primaryType",""" +
            """"domain":{"name":"AsterSignTransaction","version":"1","chainId":$chainId,"verifyingContract":"$ZERO"},""" +
            """"message":{"agent":"$AGENT"}}"""

    @Test
    fun anAsterDexTypeThatGrantsControlOrAssetsOnChain56IsADangerWithATick() {
        for (type in listOf("ApproveAgent", "UpdateAgent", "ApproveBuilder", "UpdateBuilder", "UserAssetMigrate")) {
            val review = reviewed(asterOther(type), BNB)

            val dangers = dangersOf(review)
            assertEquals(1, dangers.size, "$type needs exactly one DANGER: ${review.warnings}")
            assertNotNull(dangers.single().acknowledge)
            assertNoStoppingWarning(review)
        }
    }

    @Test
    fun anotherAsterDexTypeOnChain56IsACautionWithATickAndAnAsterDexTypeOnChain1666IsNotWarned() {
        for (type in listOf("DelAgent", "DelBuilder", "Surprise")) {
            val review = reviewed(asterOther(type), BNB)

            assertTrue(dangersOf(review).isEmpty(), type)
            assertTrue(cautionsOf(review).any { it.code == "evm.typed.aster-type" }, "no CAUTION for $type")
        }
        assertTrue(cautionsOf(reviewed(asterOther("UserAssetMigrate", 1666), BNB)).none { it.code == "evm.typed.aster-type" })
    }

    @Test
    fun aPlainAsterDexMessageGetsNoOtherTypeWarning() {
        val review = reviewed(asterMessage(56, ASTER_MASTER_MESSAGE), BNB)

        assertTrue(review.warnings.none { it.code == "evm.typed.aster-type" })
    }

    // Lighter: github.com/elliottech/lighter-go, types/txtypes/utils.go templates, GetL1SignatureBody of each transaction.
    private fun hex10(n: Long) = "0x" + "%016x".format(n)

    private val lighterTransfer = "Transfer\n\nnonce: ${hex10(7)}\nfrom: ${hex10(3)} (route ${hex10(0)})\napi key: ${hex10(2)}\n" +
        "to: ${hex10(4242)} (route ${hex10(1)})\nasset: ${hex10(3)}\namount: ${hex10(1_500_000)}\nfee: ${hex10(0)}\nchainId: ${hex10(304)}\n" +
        "memo: ${"00".repeat(32)}\nOnly sign this message for a trusted client!"

    private val lighterIntegrator = "Approve Integrator\n\nnonce: ${hex10(7)}\naccount index: ${hex10(3)}\napi key index: ${hex10(2)}\n" +
        "integrator account index: ${hex10(99)}\nmax perps taker fee: ${hex10(500)}\nmax perps maker fee: ${hex10(100)}\n" +
        "max spot taker fee: ${hex10(500)}\nmax spot maker fee: ${hex10(100)}\napproval expiry: ${hex10(1800000000000)}\n" +
        "chainId: ${hex10(304)}\nOnly sign this message for a trusted client!"

    private fun lighterReviewOf(text: String): Review =
        assertIs<EvmReviewResult.Ok>(reviewOf(text.toByteArray(Charsets.UTF_8), null, DataType.PERSONAL_MESSAGE)).review

    private fun lighterRows(review: Review): Map<String, String> =
        review.sections.single { it.heading == "Lighter" }.rows.associate { it.label to it.value.text() }

    @Test
    fun aLighterRegisterMessageIsLabelledAndADanger() {
        val review = lighterReviewOf(LIGHTER_MESSAGE)

        val rows = lighterRows(review)
        assertEquals("3", rows["Account index"])
        assertEquals("2", rows["API key index"])
        assertEquals("5", rows["Nonce"])
        assertEquals(1, dangersOf(review).size)
        assertShownWhole(review, LIGHTER_MESSAGE)
        assertNoStoppingWarning(review)
    }

    @Test
    fun aLighterApproveIntegratorMessageIsLabelledAndADanger() {
        val review = lighterReviewOf(lighterIntegrator)

        assertEquals("99", lighterRows(review)["Integrator account index"])
        assertEquals(1, dangersOf(review).size)
        assertShownWhole(review, lighterIntegrator)
    }

    @Test
    fun aLighterTransferShowsTheDestinationAccountAndTheAmount() {
        val review = lighterReviewOf(lighterTransfer)

        val rows = lighterRows(review)
        assertEquals("4242", rows["To account index"])
        assertEquals("1500000 (smallest units of the asset)", rows["Amount"])
        assertTrue(dangersOf(review).isEmpty())
        assertTrue(cautionsOf(review).any { it.message.contains("account 4242") })
        assertShownWhole(review, lighterTransfer)
    }

    @Test
    fun aLighterSubAccountAndAirdropMessageAreLabelledWithoutADanger() {
        val sub = "Create Lighter Sub Account\n\nmaster account index: ${hex10(3)}\nOnly sign this message for a trusted client!"
        val airdrop = "Airdrop Allocation\n\nallocations: 1:100\nchainId: ${hex10(304)}\nOnly sign this message for a trusted client!"
        for (text in listOf(sub, airdrop)) {
            val review = lighterReviewOf(text)

            assertEquals(1, review.sections.count { it.heading == "Lighter" })
            assertTrue(dangersOf(review).isEmpty())
            assertShownWhole(review, text)
        }
    }

    @Test
    fun aMessageThatOpensAsALighterMessageButDoesNotFitItsTemplateIsWarnedAndShownWhole() {
        val variants = listOf(
            LIGHTER_MESSAGE.replace("\nnonce: ", "\n nonce: "),
            LIGHTER_MESSAGE.replace("Only sign", "Always sign"),
            LIGHTER_MESSAGE + "\nExtra",
            LIGHTER_MESSAGE.replace("account index: 0x0000000000000003", "account index: 3"),
            LIGHTER_MESSAGE.replace("Register Lighter Account", "register lighter account"),
            lighterTransfer.replace("to: ${hex10(4242)}", "to: 4242"),
            lighterTransfer.replace("memo: ${"00".repeat(32)}", "memo: hello"),
            lighterIntegrator.replace("\nchainId: ${hex10(304)}", ""),
        )
        for (text in variants) {
            val review = lighterReviewOf(text)

            assertTrue(cautionsOf(review).any { it.code == "evm.personal-message.lighter" }, "no warning for: $text")
            assertTrue(rowsOf(review).none { it.label == "Action" }, "a malformed message is labelled: $text")
            assertShownWhole(review, text)
        }
    }

    @Test
    fun aPersonalMessageThatIsNotLighterGetsNoLighterReview() {
        for (text in listOf("Sign in to Example", "Transfer 5 ETH to Alice", "Please register")) {
            assertTrue(lighterReviewOf(text).sections.none { it.heading == "Lighter" }, text)
        }
    }
}
