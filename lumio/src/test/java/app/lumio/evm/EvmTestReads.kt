package app.lumio.evm

import app.lumio.data.MonotonicClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.rpc.CustomRpc
import app.lumio.data.rpc.JsonRpc
import app.lumio.data.sched.Scheduler
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * EVM reads for a test. Each chain in [settings] reads from the owner's address, which is a local MockWebServer URL, so plain HTTP is allowed.
 * The public pool has no provider here, so a test never reaches the network.
 */
internal fun testReads(settings: EvmSettings): EvmReads {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val configs = { mapOf(CustomRpc.ID to ProviderConfig(chainUrls = settings.rpc.mapKeys { it.key.toString() })) }
    val http = JsonRpc.newHttp(timeoutMs = 2_000L)
    return EvmReads(
        pool = Scheduler(emptyList(), configs, http, MonotonicClock, Random(7), scope),
        owner = Scheduler(listOf(CustomRpc(allowLocalHttp = true)), configs, http, MonotonicClock, Random(7), scope),
        settings = { settings },
    )
}
