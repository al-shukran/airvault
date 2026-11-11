package app.lensly.ui.sign

import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.TokenEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        networkName = network?.name ?: UNKNOWN_CHAIN_NAME,
        chainId = 8453L,
        fromAccountLabel = "ACCOUNT 1",
        fromAddress = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F",
        toAddress = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2",
        amount = "1",
        symbol = "USDC",
        tokenContract = tokenContract,
        function = "Token transfer",
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
}
