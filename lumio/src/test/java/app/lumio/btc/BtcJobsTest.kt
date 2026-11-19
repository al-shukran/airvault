package app.lumio.btc

import app.lumio.data.FakeClock
import app.lumio.data.FakeKvDao
import app.lumio.data.FakeProvider
import app.lumio.data.MonotonicClock
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CacheKeys
import app.lumio.data.cache.CachePolicy
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.Op
import app.lumio.data.sched.Scheduler
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test

/** A block is cached for good, and a transaction only once it has six confirmations. An unconfirmed one is read again. */
class BtcJobsTest {
    private val net = NetworkId(Networks.BTC_TESTNET4, testnet = true)
    private val txid = "e".repeat(64)
    private val hash = "f".repeat(64)

    private fun cache() = Cache(FakeKvDao(), CacheKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }), FakeClock(1_000L))

    private fun provider() = FakeProvider("blockstream", networks = setOf(net), ops = setOf(Op.TX, Op.BLOCK))

    private fun scheduler(provider: FakeProvider, cache: Cache, scope: CoroutineScope): Scheduler = Scheduler(
        providers = listOf(provider),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = MonotonicClock,
        random = Random(4),
        scope = scope,
        cache = { cache },
    )

    @Test
    fun aTransactionWithSixConfirmationsIsReadOnceAndThenServedFromTheCache() = runBlocking {
        val provider = provider()
        provider.outcomes.add(app.lumio.btc.EsploraTxStatus(txid, confirmed = true, blockHeight = 100L, blockHash = hash, blockTimeSec = 1L))
        val scheduler = scheduler(provider, cache(), this)

        val first = scheduler.transaction(net, txid, tipHeight = 105L)
        val second = scheduler.transaction(net, txid, tipHeight = 105L)

        assertEquals(first, second)
        assertEquals(1, provider.calls, "six confirmations make the answer final, so it is not asked for again")
    }

    @Test
    fun aTransactionWithFewerThanSixConfirmationsIsReadAgain() = runBlocking {
        val provider = provider()
        repeat(2) { provider.outcomes.add(app.lumio.btc.EsploraTxStatus(txid, true, 100L, hash, 1L)) }
        val scheduler = scheduler(provider, cache(), this)

        scheduler.transaction(net, txid, tipHeight = 104L)
        scheduler.transaction(net, txid, tipHeight = 104L)

        assertEquals(2, provider.calls, "five confirmations are not final yet")
    }

    @Test
    fun anUnconfirmedTransactionIsNeverKeptForGood() = runBlocking {
        val provider = provider()
        repeat(2) { provider.outcomes.add(app.lumio.btc.EsploraTxStatus(txid, false, null, null, null)) }
        val scheduler = scheduler(provider, cache(), this)

        scheduler.transaction(net, txid, tipHeight = 105L)
        scheduler.transaction(net, txid, tipHeight = 105L)

        assertEquals(2, provider.calls)
    }

    @Test
    fun aLastKnownBalanceOfAnAccountNoLongerPairedIsDropped() = runBlocking {
        val store = BtcStore(cache())
        store.save(lastKnown("wallet-1_TESTNET4_NATIVE_SEGWIT"))
        store.save(lastKnown("wallet-2_TESTNET4_NATIVE_SEGWIT"))

        store.retain(setOf("wallet-1_TESTNET4_NATIVE_SEGWIT"))

        assertEquals(5L, store.snapshot("wallet-1_TESTNET4_NATIVE_SEGWIT")?.confirmedSats)
        assertEquals(null, store.snapshot("wallet-2_TESTNET4_NATIVE_SEGWIT"), "a deleted wallet leaves no balance behind")
    }

    private fun lastKnown(key: String) = BtcSnapshot(
        key = key,
        identity = "tb1qidentity",
        scannedAtMs = 1L,
        tipHeight = 1L,
        confirmedSats = 5L,
        unconfirmedSats = 0L,
        nextReceiveIndex = 0,
        nextReceiveAddress = "tb1qnext",
        utxos = emptyList(),
        transactions = emptyList(),
    )

    /** A scan stored in the format before the addresses were kept reads as absent, so its account is scanned again. */
    @Test
    fun aScanStoredInTheOlderFormatReadsAsAbsent() = runBlocking {
        val cache = cache()
        cache.put(CachePolicy.LastKnown, "btc-scan/wallet-1_TESTNET4_NATIVE_SEGWIT", olderFormatScan("wallet-1_TESTNET4_NATIVE_SEGWIT"))

        assertEquals(null, BtcStore(cache).snapshot("wallet-1_TESTNET4_NATIVE_SEGWIT"))
    }

    /** One scan in the format of version 2: a record with no address list after its transactions. */
    private fun olderFormatScan(key: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(2)
            out.writeInt(1)
            out.writeUTF(key)
            out.writeUTF("tb1qidentity")
            out.writeLong(1L)
            out.writeLong(1L)
            out.writeLong(5L)
            out.writeLong(0L)
            out.writeInt(0)
            out.writeUTF("tb1qnext")
            out.writeInt(0)
            out.writeInt(0)
        }
        return bytes.toByteArray()
    }

    @Test
    fun aBlockIsCachedForGood() = runBlocking {
        val provider = provider()
        provider.outcomes.add(app.lumio.btc.EsploraBlock(hash, height = 100L, timestampSec = 1L))
        val scheduler = scheduler(provider, cache(), this)

        val first = scheduler.block(net, hash)
        val second = scheduler.block(net, hash)

        assertEquals(first, second)
        assertEquals(1, provider.calls)
    }
}
