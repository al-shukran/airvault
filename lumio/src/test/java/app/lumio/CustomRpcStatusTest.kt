package app.lumio

import app.lumio.data.Clock
import app.lumio.data.FakeProvider
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.Ops
import app.lumio.data.provider.ProviderError
import app.lumio.data.rpc.CustomRpc
import app.lumio.data.sched.Scheduler
import app.lumio.evm.ChainRpcs
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmReads
import app.lumio.evm.EvmSettings
import app.lumio.ui.settings.ProviderStatus
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/** The status of the owner's own RPC address on a chain's Network page: each chain's address has its own health, and the page reads it again on a tick. */
class CustomRpcStatusTest {

    private val ethereum = EvmChain(chainId = 1, name = "Ethereum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)
    private val sepolia = EvmChain(chainId = 11155111, name = "Sepolia", symbol = "ETH", decimals = 18, testnet = true, explorerUrl = null)
    private val address = "0x" + "11".repeat(20)

    /** Reads with a custom address on both chains. [providers] gets the fake behind each chain's scheduler, in the order the chains are first used. */
    private fun TestScope.reads(providers: MutableList<FakeProvider>): EvmReads {
        val clock = Clock { testScheduler.currentTime }
        val http = Http(OkHttpClient())
        val settings = EvmSettings(
            rpc = mapOf(
                ethereum.chainId to ChainRpcs(custom = listOf("https://one.example"), active = "https://one.example"),
                sepolia.chainId to ChainRpcs(custom = listOf("https://two.example"), active = "https://two.example"),
            ),
        )
        val nets = setOf(NetworkId(Networks.evm(ethereum.chainId), false), NetworkId(Networks.evm(sepolia.chainId), true))
        return EvmReads(
            pool = Scheduler(emptyList(), { emptyMap() }, http, clock, Random(3), this),
            newOwner = {
                val provider = FakeProvider(
                    CustomRpc.ID,
                    kind = Kind.RPC,
                    networks = nets,
                    ops = setOf(Ops.EVM_NATIVE_BALANCE),
                    now = { testScheduler.currentTime },
                )
                providers += provider
                Scheduler(listOf(provider), { emptyMap() }, http, clock, Random(3), this)
            },
            settings = { settings },
        )
    }

    private suspend fun status(reads: EvmReads, chain: EvmChain) = customRpcStatus(reads, emptyMap<String, ProviderConfig>(), chain.chainId)

    @Test
    fun aFailingAddressOnOneChainLeavesTheOtherChainsStatusAlone() = runTest {
        val providers = ArrayList<FakeProvider>()
        val reads = reads(providers)
        // Each chain's scheduler is made on first use: Ethereum's first, then Sepolia's.
        status(reads, ethereum)
        status(reads, sepolia)
        providers[1].outcomes.addAll(List(6) { ProviderError.Transient() })

        repeat(2) { runCatching { reads.balance(sepolia, address) } }
        reads.balance(ethereum, address)

        assertIs<ProviderStatus.CircuitOpen>(status(reads, sepolia))
        assertIs<ProviderStatus.Healthy>(status(reads, ethereum))
    }

    @Test
    fun aSuccessOnOneChainDoesNotClearTheStreakOfAnother() = runTest {
        val providers = ArrayList<FakeProvider>()
        val reads = reads(providers)
        status(reads, ethereum)
        status(reads, sepolia)
        providers[0].outcomes.addAll(List(6) { ProviderError.Transient() })

        runCatching { reads.balance(ethereum, address) }
        reads.balance(sepolia, address)
        runCatching { reads.balance(ethereum, address) }

        assertIs<ProviderStatus.CircuitOpen>(status(reads, ethereum))
    }

    @Test
    fun aChainWithNoReadsYetIsReady() = runTest {
        assertEquals(ProviderStatus.Ready, status(reads(ArrayList()), ethereum))
    }

    @Test
    fun theStatusIsReadAgainOnEveryTick() = runTest {
        var reads = 0
        val seen = polled(STATUS_POLL_MS) { ++reads }.take(3).toList()

        assertEquals(listOf(1, 2, 3), seen)
        assertEquals(2 * STATUS_POLL_MS, testScheduler.currentTime)
    }
}
