package app.lensly.ui.sign

import app.lensly.app.sign.SOLANA_NETWORK
import app.lensly.core.algorand.AlgorandNetwork
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The network a review names, from the chain ID of an EVM request, the network of a Bitcoin request, and the genesis ID of an Algorand one. */
class ReviewNetworkNameTest {
    private fun entry(chainId: Long, name: String, testnet: Boolean) =
        NetworkEntry(chainId = chainId, name = name, nativeSymbol = "X", feeModel = FeeModel.L1, explorerName = null, testnet = testnet)

    @Test
    fun aRegisteredTestnetChainIdIsNamedByItsRegistryEntry() {
        assertEquals("Monad testnet", evmNetworkName(entry(10143, "Monad testnet", testnet = true), 10143))
    }

    @Test
    fun aRegisteredMainnetChainIdIsNamedByItsRegistryEntry() {
        assertEquals("Ethereum", evmNetworkName(entry(1, "Ethereum", testnet = false), 1))
    }

    @Test
    fun anUnregisteredChainIdIsNamedUnknownWithTheId() {
        assertEquals("Unknown network (chain id 999999)", evmNetworkName(null, 999_999L))
        assertEquals("Unknown network (chain id 999999)", unknownNetworkName(999_999L))
    }

    @Test
    fun theBitcoinNetworksAreNamedWithTheirChain() {
        assertEquals("Bitcoin Mainnet", bitcoinNetworkName(BtcNetwork.MAINNET))
        assertEquals("Bitcoin Testnet4", bitcoinNetworkName(BtcNetwork.TESTNET4))
    }

    @Test
    fun theAlgorandGenesisIdsAreNamedByTheirNetwork() {
        assertEquals("Algorand Testnet", AlgorandNetwork.ofGenesisId("testnet-v1.0")?.displayName)
        assertEquals("Algorand Mainnet", AlgorandNetwork.ofGenesisId("mainnet-v1.0")?.displayName)
    }

    @Test
    fun aGenesisIdOfAnotherNetworkIsNotNamed() {
        assertNull(AlgorandNetwork.ofGenesisId("betanet-v1.0"))
    }

    @Test
    fun theSolanaRequestIsNamedSolana() {
        assertEquals("Solana", SOLANA_NETWORK)
    }
}
