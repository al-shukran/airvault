package app.lensly.ui.sign

import app.airvault.chains.algorand.AlgorandNetwork
import app.lensly.app.sign.SOLANA_NETWORK
import app.lensly.core.btc.BtcNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The network a review names when the registry does not hold an EVM chain ID, the network of a Bitcoin request, and the genesis ID of an
 * Algorand one. A registered EVM chain is named by the review builder, which EvmReviewTest checks.
 */
class ReviewNetworkNameTest {

    @Test
    fun anUnregisteredChainIdIsNamedUnknownWithTheId() {
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
