package app.lumio.data.sched

import app.lumio.data.BTC
import app.lumio.data.Clock
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.Utxo
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/** A call whose requests the test sets. Each request after the first takes its own token, as a Blockchair page does. */
class PageChargeTest {

    /** One call sends [requests] HTTP requests: the scheduler pays for the first, and each later one is charged through [Http.charge]. */
    private class PagedProvider(private val requests: Int) : Provider {
        override val id: String = "paged"
        override val kind: Kind = Kind.DATA
        override val traits: Set<Trait> = setOf(Trait.KEYLESS)

        override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean = op == Ops.BTC_UTXOS

        /** One token a second, and one held: the first request takes the held token, and every later one waits a second for its own. */
        override fun defaultLimits(cfg: ProviderConfig): Limits = Limits(rps = 1.0, burst = 1, maxConcurrent = 1)

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
            repeat(requests - 1) { http.charge() }
            return emptyList<Utxo>() as T
        }
    }

    private fun TestScope.scheduler(requests: Int): Scheduler = Scheduler(
        providers = listOf(PagedProvider(requests)),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = Clock { testScheduler.currentTime },
        random = Random(3),
        scope = this,
    )

    /** A BIP-173 example address, which is public. */
    private val address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

    @Test
    fun aCallOfNPagesTakesNTokensAndWaitsForEachTokenAfterTheFirst() = runTest {
        scheduler(requests = 3).run(AddressUtxos(BTC, address), Priority.BACKGROUND)

        // The admission took the held token. The second and third requests each waited a second for theirs.
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun aCallWithOneRequestTakesOnlyItsAdmissionToken() = runTest {
        scheduler(requests = 1).run(AddressUtxos(BTC, address), Priority.BACKGROUND)

        assertEquals(0L, testScheduler.currentTime)
    }
}
