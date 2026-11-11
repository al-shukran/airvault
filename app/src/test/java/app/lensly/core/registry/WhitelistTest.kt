package app.lensly.core.registry

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

private const val TRANSFER_SIGNATURE = "transfer(address,uint256)"
private const val TRANSFER_SELECTOR = "0xa9059cbb"
private const val SWAP_SIGNATURE = "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)"
private const val SWAP_SELECTOR = "0x38ed1739"
private const val APPROVE_SELECTOR = "0x095ea7b3"
private const val CONTRACT = "0x5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"
private const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
private const val CODE_DIGEST = "0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private val ETHEREUM = NetworkEntry(1L, "Ethereum", "ETH", 18, FeeModel.L1, "Etherscan", false)
private val BASE = NetworkEntry(8453L, "Base", "ETH", 18, FeeModel.L2_ROLLUP, "Basescan", false)

private fun swap(chainId: Long = 1L, contract: String = CONTRACT) = WhitelistEntry(
    chainId = chainId,
    contract = contract,
    selector = SWAP_SELECTOR,
    signature = SWAP_SIGNATURE,
    meaning = "Swap an exact amount along the route",
)

/** The whitelist as the signed package and the build hold it: the signature, its selector, and the meaning. */
class WhitelistEntryTest {

    @Test
    fun selectorIsTheKeccakHashOfTheSignature() {
        assertEquals(TRANSFER_SELECTOR, WhitelistEntry.selectorOf(TRANSFER_SIGNATURE))
        assertEquals(SWAP_SELECTOR, WhitelistEntry.selectorOf(SWAP_SIGNATURE))
    }

    @Test
    fun aSelectorThatIsNotTheSignaturesIsRefused() {
        assertFailsWith<IllegalArgumentException> { swap().copy(selector = APPROVE_SELECTOR) }
    }

    @Test
    fun aSignatureMustBeInCanonicalForm() {
        assertFailsWith<IllegalArgumentException> { swap().copy(signature = "swap") }
        assertFailsWith<IllegalArgumentException> { swap().copy(signature = "swap(uint256 amount)") }
    }

    @Test
    fun aProxyBindingNamesAnImplementation() {
        assertEquals(IMPLEMENTATION, ProxyBinding(implementationAddress = IMPLEMENTATION).implementationAddress)
        assertEquals(CODE_DIGEST, ProxyBinding(implementationCodeHash = CODE_DIGEST).implementationCodeHash)
        assertFailsWith<IllegalArgumentException> { ProxyBinding() }
        assertFailsWith<IllegalArgumentException> { ProxyBinding(implementationCodeHash = "0x1234") }
    }

    @Test
    fun aMeaningMustSayWhatTheCallDoes() {
        assertFailsWith<IllegalArgumentException> { swap().copy(meaning = " ") }
    }

    @Test
    fun anEntryIsNotAProxyUnlessItNamesAnImplementation() {
        assertNull(swap().proxy)
    }

    @Test
    fun everyPolicyCodeReadsBackAsItsPolicyAndAnUnknownCodeReadsAsNone() {
        for (policy in ProxyPolicy.entries) assertEquals(policy, ProxyPolicy.ofCode(policy.code))
        assertNull(ProxyPolicy.ofCode(9))
    }
}

class WhitelistRegistryTest {

    private val registry = Registry(listOf(ETHEREUM, BASE), emptyList(), listOf(swap()))

    @Test
    fun aLookupFindsTheEntryOfItsChainContractAndSelector() {
        assertEquals(swap(), registry.whitelist(1L, CONTRACT, SWAP_SELECTOR))
    }

    @Test
    fun theContractAndSelectorAreMatchedWithoutRegardToCase() {
        val entry = registry.whitelist(1L, CONTRACT.replace('a', 'A'), SWAP_SELECTOR.uppercase().replace("0X", "0x"))

        assertEquals(SWAP_SIGNATURE, entry?.signature)
    }

    @Test
    fun aMissIsNullForAnotherChainContractOrSelector() {
        assertNull(registry.whitelist(8453L, CONTRACT, SWAP_SELECTOR))
        assertNull(registry.whitelist(1L, "0x6666666666666666666666666666666666666666", SWAP_SELECTOR))
        assertNull(registry.whitelist(1L, CONTRACT, APPROVE_SELECTOR))
    }

    @Test
    fun aRepeatedChainContractAndSelectorIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            Registry(listOf(ETHEREUM), emptyList(), listOf(swap(), swap().copy(meaning = "Again")))
        }
    }

    @Test
    fun anEntryOnAChainNotInTheRegistryIsRefused() {
        assertFailsWith<IllegalArgumentException> { Registry(listOf(ETHEREUM), emptyList(), listOf(swap(chainId = 10L))) }
    }

    @Test
    fun replacingTheWhitelistKeepsTheNetworksAndTokens() {
        val replaced = registry.withWhitelist(emptyList())

        assertNull(replaced.whitelist(1L, CONTRACT, SWAP_SELECTOR))
        assertEquals(registry.networks(), replaced.networks())
    }

    @Test
    fun addingNetworksKeepsTheWhitelist() {
        val added = registry.withAdditions(listOf(NetworkEntry(10L, "Optimism", "ETH", 18, FeeModel.L2_ROLLUP, null, false)), emptyList())

        assertEquals(SWAP_SIGNATURE, added.whitelist(1L, CONTRACT, SWAP_SELECTOR)?.signature)
    }
}
