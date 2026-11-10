package app.lensly.ui.sign

import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import org.junit.Test
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

        assertTrue(known.canApprove(everyFactShown = true, registryChecked = false))
        assertFalse(known.canApprove(everyFactShown = false, registryChecked = false))
        assertFalse(unknown.canApprove(everyFactShown = true, registryChecked = false))
        assertTrue(unknown.canApprove(everyFactShown = true, registryChecked = true))
        assertFalse(unknown.canApprove(everyFactShown = false, registryChecked = true))
    }
}
