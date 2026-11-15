package app.lumio.evm

import org.junit.Test
import kotlin.test.assertEquals

/** The Testnet side lists Monad testnet first, and every other chain keeps its place. */
class EvmCatalogOrderTest {
    private fun chain(id: Long, testnet: Boolean) = EvmChain(id, "chain $id", "SYM", 18, testnet, explorerUrl = null)

    @Test
    fun monadTestnetLeadsTheTestnetsAndSepoliaFollowsIt() {
        val ordered = EvmCatalog.primaryTestnetFirst(
            listOf(chain(1, false), chain(11155111, true), chain(10143, true), chain(10, false)),
        )

        assertEquals(listOf(10143L, 11155111L), ordered.filter { it.testnet }.map { it.chainId })
        assertEquals(listOf(10143L, 1L, 11155111L, 10L), ordered.map { it.chainId })
    }

    @Test
    fun withoutMonadTheOrderIsUnchanged() {
        val chains = listOf(chain(1, false), chain(11155111, true), chain(42161, false))

        assertEquals(chains.map { it.chainId }, EvmCatalog.primaryTestnetFirst(chains).map { it.chainId })
    }
}
