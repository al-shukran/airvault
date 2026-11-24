package app.lumio.data.provider

import app.lumio.data.FakeProvider
import app.lumio.data.rpc.CustomRpc
import app.lumio.data.provider.etherscan.EtherscanProvider
import app.lumio.evm.EvmChain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which chains a provider serves is read with a probe key, so a key-required provider lists its chains before the owner sets a key. The probe is
 * never sent: membership is read from [Provider.supports] alone, and no request goes out to find it.
 */
class ProviderMembershipTest {

    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val polygon = EvmChain(137L, "Polygon", "POL", 18, testnet = false, explorerUrl = null)
    private val hyperEvm = EvmChain(999L, "HyperEVM", "HYPE", 18, testnet = false, explorerUrl = null)
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, testnet = true, explorerUrl = null)
    private val chains = listOf(ethereum, polygon, hyperEvm, sepolia)

    @Test
    fun aKeyRequiredProviderListsItsFreeMainnetsBeforeAKeyIsSet() {
        assertEquals(listOf(ethereum, polygon), ProviderCatalog.evmChainsOf(EtherscanProvider(), chains))
    }

    @Test
    fun membershipIsReadWithTheProbeAndSendsNoRequest() {
        val keyed = FakeProvider("keyed", traits = setOf(Trait.KEY_REQUIRED))

        assertEquals(listOf(ethereum), ProviderCatalog.evmChainsOf(keyed, chains))
        assertEquals(0, keyed.calls)
    }

    @Test
    fun theCustomRpcListsTheChainsTheOwnerSetAnAddressFor() {
        val custom = CustomRpc(allowLocalHttp = false)

        assertEquals(listOf(ethereum), ProviderCatalog.evmChainsOf(custom, chains, owner = mapOf("1" to "https://rpc.example.org")))
    }

    @Test
    fun theCustomRpcListsNoChainWithoutAnOwnerAddress() {
        assertEquals(emptyList<EvmChain>(), ProviderCatalog.evmChainsOf(CustomRpc(allowLocalHttp = false), chains))
    }
}
