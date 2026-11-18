package app.lensly.ui.sign

import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.registry.DenylistCondition
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.theme.SpaceMonoFamily
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TxReviewTest {

    private val base = NetworkEntry(
        chainId = 8453L,
        name = "Base",
        nativeSymbol = "ETH",
        feeModel = FeeModel.L2_ROLLUP,
        explorerName = "Basescan",
        testnet = false,
    )
    private val usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
    private val token = TokenEntry(chainId = 8453L, contract = usdc, symbol = "USDC", name = "USD Coin", decimals = 6)

    /** A review whose chain and token are the given ones. A null [network] is an unknown chain. */
    private fun review(network: NetworkEntry?, tokenContract: String?, token: TokenEntry?) = TxReview(
        networkName = evmNetworkName(network, 999_999L),
        chainId = 8453L,
        fromAccountLabel = "ACCOUNT 1",
        fromAddress = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F",
        toAddress = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2",
        amount = "1",
        symbol = "USDC",
        tokenContract = tokenContract,
        function = "Token Transfer",
        params = emptyList(),
        fee = emptyList(),
        feeNote = null,
        warnings = emptyList(),
        nonce = 0L,
        txType = "EIP-1559",
        network = network,
        token = token,
    )

    @Test
    fun aKnownNativeTransferNeedsNoRegistryConfirmation() {
        assertFalse(review(base, tokenContract = null, token = null).needsRegistryConfirmation)
    }

    @Test
    fun aKnownTokenOnAKnownChainNeedsNoRegistryConfirmation() {
        assertFalse(review(base, tokenContract = usdc, token = token).needsRegistryConfirmation)
    }

    @Test
    fun anUnknownChainNeedsRegistryConfirmation() {
        assertTrue(review(network = null, tokenContract = null, token = null).needsRegistryConfirmation)
    }

    @Test
    fun anUnknownTokenOnAKnownChainNeedsRegistryConfirmation() {
        assertTrue(review(base, tokenContract = usdc, token = null).needsRegistryConfirmation)
    }

    @Test
    fun approveWaitsForTheRegistryCheckOnlyWhenTheReviewNeedsIt() {
        val known = review(base, tokenContract = usdc, token = token)
        val unknown = review(network = null, tokenContract = null, token = null)

        assertTrue(known.canApprove(everyFactShown = true, checked = emptySet()))
        assertFalse(known.canApprove(everyFactShown = false, checked = emptySet()))
        assertFalse(unknown.canApprove(everyFactShown = true, checked = emptySet()))
        assertTrue(unknown.canApprove(everyFactShown = true, checked = setOf(ReviewCheck.REGISTRY)))
        assertFalse(unknown.canApprove(everyFactShown = false, checked = setOf(ReviewCheck.REGISTRY)))
    }

    /** A whitelisted call on Base, with its contract and selector as the pipeline builds them. */
    private fun whitelisted(proxy: ProxyBinding?) = review(base, tokenContract = null, token = null).copy(
        whitelisted = WhitelistedCall(
            signature = "approveAndDeposit(uint256,address)",
            meaning = "Deposit into the pool",
            call = UndecodedCall(
                target = usdc,
                networkName = "Base",
                chainId = 8453L,
                value = "0 ETH",
                selector = "7acb7757",
                words = listOf("00".repeat(32)),
                proxy = proxy,
            ),
        ),
    )

    @Test
    fun aWhitelistedCallWaitsForItsOwnCheckBeforeItSigns() {
        val call = whitelisted(proxy = null)

        assertEquals(listOf(ReviewCheck.WHITELIST), call.requiredChecks)
        assertFalse(call.canApprove(everyFactShown = true, checked = emptySet()))
        assertTrue(call.canApprove(everyFactShown = true, checked = setOf(ReviewCheck.WHITELIST)))
    }

    @Test
    fun aProxyUnderWarnAndConfirmNeedsASecondCheck() {
        val proxy = whitelisted(proxy = ProxyBinding(implementationAddress = "0x7777777777777777777777777777777777777777"))

        assertEquals(listOf(ReviewCheck.WHITELIST, ReviewCheck.UPGRADE), proxy.requiredChecks)
        assertFalse(proxy.canApprove(everyFactShown = true, checked = setOf(ReviewCheck.WHITELIST)))
        assertTrue(proxy.canApprove(everyFactShown = true, checked = setOf(ReviewCheck.WHITELIST, ReviewCheck.UPGRADE)))
    }

    @Test
    fun aReviewWithNothingToPointOutHasNoWarnings() {
        assertFalse(review(base, tokenContract = null, token = null).hasWarnings)
    }

    @Test
    fun everyKindOfWarningMakesTheReviewHaveWarnings() {
        val plain = review(base, tokenContract = null, token = null)
        val denylisted = plain.copy(
            denylisted = listOf(
                DenylistEntry(
                    chainId = 8453L,
                    selector = "0x095ea7b3",
                    condition = DenylistCondition.UNLIMITED,
                    severity = DenylistSeverity.WARN,
                    reason = "Unlimited approval",
                ),
            ),
        )
        val nestedWarning = plain.copy(
            nested = listOf(NestedCall(depth = 1, target = null, function = "approve", params = emptyList(), warnings = listOf("Unlimited"))),
        )

        assertTrue(plain.copy(warnings = listOf("A plain warning")).hasWarnings)
        assertTrue(review(network = null, tokenContract = null, token = null).hasWarnings)
        assertTrue(denylisted.hasWarnings)
        assertTrue(whitelisted(proxy = null).hasWarnings)
        assertTrue(nestedWarning.hasWarnings)
    }

    private val operator = "0x2222222222222222222222222222222222222222"
    private val collection = "0x3333333333333333333333333333333333333333"

    /** An approval for every token of [collection] to [operator], granted when [approved] and revoked when it is not. */
    private fun approvalForAll(approved: Boolean) = review(base, tokenContract = collection, token = null).copy(
        function = "setApprovalForAll",
        toAddress = operator,
        amount = "",
        symbol = "",
        params = listOf("operator" to operator, "approved" to approved.toString()),
    )

    @Test
    fun anApprovalForAllNamesItsOperatorAndItsCollectionInTheHeadline() {
        val grant = evmSentence(approvalForAll(approved = true), SpaceMonoFamily).text
        val revoke = evmSentence(approvalForAll(approved = false), SpaceMonoFamily).text

        assertEquals("Give ${shortAddress(operator)} control of ${shortAddress(collection)}", grant)
        assertEquals("Remove control of ${shortAddress(collection)} from ${shortAddress(operator)}", revoke)
    }

    @Test
    fun theOriginIsShownAsAClaimThatLensleyDoesNotVerify() {
        val plain = review(base, tokenContract = null, token = null)
        val claimed = evmFacts(plain.copy(origin = "app.uniswap.org"))

        assertTrue(claimed.contains(ReviewFact(label = "Claimed by request (not verified)", value = "app.uniswap.org", mono = false)))
        assertTrue(evmFacts(plain).contains(ReviewFact(label = "Requested by", value = "Not stated", mono = false)))
    }

    /** The tick of an unknown call sits on the first row that warns of it, and is owed once for the whole review. */
    @Test
    fun theUnknownCallTickSitsOnItsWarningRowAndIsOwedOnce() {
        val unknown = review(base, tokenContract = null, token = null).copy(
            warnings = listOf(UNKNOWN_CALL_WARNING),
            nested = listOf(
                NestedCall(
                    depth = 1,
                    target = null,
                    function = "0x12345678",
                    params = emptyList(),
                    warnings = listOf(UNKNOWN_CALL_WARNING),
                ),
            ),
        )

        assertEquals(listOf(ReviewCheck.UNKNOWN_CALL), unknown.requiredChecks)
        assertFalse(unknown.canApprove(everyFactShown = true, checked = emptySet()))
        assertTrue(unknown.canApprove(everyFactShown = true, checked = setOf(ReviewCheck.UNKNOWN_CALL)))

        val rows = evmRiskItems(unknown, emptySet()) { _, _ -> }
        assertEquals(listOf("I understand Lensly cannot say what this does."), rows.mapNotNull { it.check?.label })
        assertTrue(rows[0].check != null)
        assertNull(rows[1].check)
    }

    @Test
    fun aPlainWarningAsksForNoTick() {
        val warned = review(base, tokenContract = null, token = null).copy(warnings = listOf("A plain warning"))

        assertTrue(warned.requiredChecks.isEmpty())
    }

    /** An unlimited approval is signable only once the owner ticks that the spender can take every token. */
    @Test
    fun anUnlimitedApprovalWaitsForItsTickBeforeItSigns() {
        val unlimited = review(base, tokenContract = usdc, token = token).copy(warnings = listOf(unlimitedApprovalWarning("USDC")))

        assertEquals(listOf(ReviewCheck.UNLIMITED), unlimited.requiredChecks)
        assertFalse(unlimited.canApprove(everyFactShown = true, checked = emptySet()))
        assertTrue(unlimited.canApprove(everyFactShown = true, checked = setOf(ReviewCheck.UNLIMITED)))
    }

    @Test
    fun anUnlimitedApprovalInsideAMulticallAsksForTheSameTick() {
        val inBatch = review(base, tokenContract = null, token = null).copy(
            nested = listOf(
                NestedCall(
                    depth = 1,
                    target = usdc,
                    function = "approve",
                    params = emptyList(),
                    warnings = listOf(unlimitedApprovalWarning(null)),
                ),
            ),
        )

        assertEquals(listOf(ReviewCheck.UNLIMITED), inBatch.requiredChecks)
    }

    @Test
    fun aLimitedApprovalAsksForNoUnlimitedTick() {
        val limited = review(base, tokenContract = usdc, token = token).copy(function = "approve")

        assertTrue(limited.requiredChecks.isEmpty())
    }
}
