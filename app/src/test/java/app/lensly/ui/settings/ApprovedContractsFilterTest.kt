package app.lensly.ui.settings

import app.airvault.abi.WhitelistParam
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.NetworkKind
import app.lensly.core.registry.WhitelistEntry
import kotlin.test.assertEquals
import org.junit.Test

class ApprovedContractsFilterTest {

    private val ethereum = network(1L, "Ethereum")
    private val base = network(8453L, "Base")

    private val aaveEthereum = entry(
        chainId = 1L,
        contract = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2",
        selector = "0x617ba037",
        signature = "supply(address,uint256,address,uint16)",
        meaning = "Aave V3: deposit an asset into the pool to earn interest.",
    )
    private val aaveBase = aaveEthereum.copy(
        chainId = 8453L,
        contract = "0xa238dd80c259a72e81d7e4664a9801593f98d1c5",
    )
    private val lido = entry(
        chainId = 1L,
        contract = "0x7f39c581f595b53c5cb19bd0b3f8da6c935e2ca0",
        selector = "0xea598cb0",
        signature = "wrap(uint256)",
        meaning = "Lido wstETH: wrap stETH into wstETH.",
    )
    private val entries = listOf(aaveEthereum, aaveBase, lido)
    private val networks = listOf(ethereum, base)

    @Test
    fun aBlankQueryFindsEveryEntryInTheOrderGiven() {
        assertEquals(entries, filterApprovals(entries, networks, ""))
        assertEquals(entries, filterApprovals(entries, networks, "   "))
    }

    @Test
    fun theProtocolMatchesIgnoringCase() {
        assertEquals(listOf(aaveEthereum, aaveBase), filterApprovals(entries, networks, "AAVE v3"))
    }

    @Test
    fun theChainMatchesByNameOrByChainId() {
        assertEquals(listOf(aaveBase), filterApprovals(entries, networks, "base"))
        assertEquals(listOf(aaveBase), filterApprovals(entries, networks, "8453"))
    }

    @Test
    fun theAddressAndTheSelectorMatchByPrefix() {
        assertEquals(listOf(lido), filterApprovals(entries, networks, "0x7f39c5"))
        assertEquals(listOf(lido), filterApprovals(entries, networks, "0xea598cb0"))
    }

    @Test
    fun everyWordOfTheQueryMustMatch() {
        assertEquals(listOf(aaveBase), filterApprovals(entries, networks, "aave base"))
        assertEquals(emptyList(), filterApprovals(entries, networks, "aave lido"))
    }

    @Test
    fun aQueryWithNoMatchFindsNothing() {
        assertEquals(emptyList(), filterApprovals(entries, networks, "no such call"))
    }

    @Test
    fun theProtocolIsTheTextBeforeTheFirstColon() {
        assertEquals("Aave V3", protocolOf(aaveEthereum))
        assertEquals("Lido wstETH", protocolOf(lido))
        assertEquals("", protocolOf(aaveEthereum.copy(meaning = "Deposit with no protocol named")))
    }

    @Test
    fun aShortAddressKeepsTheFirstSixAndTheLastFourCharacters() {
        assertEquals("0x7f39…2ca0", shortAddress("0x7f39c581f595b53c5cb19bd0b3f8da6c935e2ca0"))
    }

    private fun network(chainId: Long, name: String) = NetworkEntry(
        chainId = chainId,
        name = name,
        nativeSymbol = "ETH",
        feeModel = FeeModel.L1,
        explorerName = null,
        testnet = false,
        featured = false, kind = NetworkKind.LAYER_1, parentChainId = null,
    )

    private fun entry(
        chainId: Long,
        contract: String,
        selector: String,
        signature: String,
        meaning: String,
    ) = WhitelistEntry(
        chainId = chainId,
        contract = contract,
        selector = selector,
        signature = signature,
        meaning = meaning,
        params = signature.substringAfter('(').substringBefore(')').split(',')
            .map { WhitelistParam(name = "arg", type = it) },
        upgradeable = false,
    )
}
