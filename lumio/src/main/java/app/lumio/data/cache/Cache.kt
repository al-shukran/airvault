package app.lumio.data.cache

import app.lensly.core.vault.Aead
import app.lensly.core.vault.VaultSession
import app.lumio.data.Clock
import app.lumio.data.db.KvDao
import app.lumio.data.db.KvRow
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The time a [CachePolicy.ShortTtl] value stays valid by default. */
const val DEFAULT_SHORT_TTL_MS = 60_000L

/** The most stored bytes that immutable values may take before the least recently used ones are evicted. */
const val IMMUTABLE_CAP_BYTES = 64L * 1024 * 1024

/** How a cached value is kept, and how long it stays valid. */
sealed interface CachePolicy {
    /** Kept in the database until the size cap evicts it. For data that cannot change, such as a block or a confirmed transaction. */
    data object Immutable : CachePolicy

    /** Kept in memory for [ttlMs], then dropped. For balances, stats and UTXOs. */
    data class ShortTtl(val ttlMs: Long = DEFAULT_SHORT_TTL_MS) : CachePolicy {
        init {
            require(ttlMs > 0) { "A time to live is above zero." }
        }
    }

    /** Kept in the database until a newer value replaces it. The caller shows it with its time, and marks it stale once it is old. */
    data object LastKnown : CachePolicy
}

/** A cached value, and the wall-clock time it was stored, or for [CachePolicy.Immutable], last read. */
class CacheEntry(val value: ByteArray, val atMs: Long)

/** How a typed result becomes the bytes the cache stores, and back. */
interface Codec<T> {
    fun encode(value: T): ByteArray

    fun decode(bytes: ByteArray): T
}

/** A result's cache key and policy, with the codec for its type. The scheduler reads and fills the cache with it. */
class CacheUse<T>(val policy: CachePolicy, val key: String, val codec: Codec<T>)

/**
 * The two keys of the cache. [mac] names the entries, and [enc] seals their values. Both come from the open vault session, so a locked vault
 * leaves nothing to read.
 */
class CacheKeys(val mac: ByteArray, val enc: ByteArray) {
    companion object {
        private const val MAC_PURPOSE = "lumio-cache-v1/mac"
        private const val ENC_PURPOSE = "lumio-cache-v1/enc"

        /** Derives both keys from [session]. */
        fun from(session: VaultSession): CacheKeys =
            CacheKeys(session.deriveKey(MAC_PURPOSE), session.deriveKey(ENC_PURPOSE))
    }
}

/**
 * The cache. [CachePolicy.Immutable] and [CachePolicy.LastKnown] values live in the database. Each entry is named by an HMAC of its policy and
 * key, and its value is sealed under [CacheKeys.enc] with the name and the policy as associated data. [CachePolicy.ShortTtl] values live in
 * memory. Stamps come from [clock], which should be the wall clock.
 */
class Cache(
    private val dao: KvDao,
    private val keys: CacheKeys,
    private val clock: Clock,
    private val immutableCapBytes: Long = IMMUTABLE_CAP_BYTES,
) {
    private val shortLived = HashMap<String, CacheEntry>()

    /** The value under [key] for [policy], or null when there is none, it has expired, or it does not open under [keys]. */
    suspend fun get(policy: CachePolicy, key: String): CacheEntry? = when (policy) {
        is CachePolicy.ShortTtl -> getShortLived(key, policy.ttlMs)
        CachePolicy.Immutable -> getStored(IMMUTABLE, key, touch = true)
        CachePolicy.LastKnown -> getStored(LAST_KNOWN, key, touch = false)
    }

    /** Stores [value] under [key] for [policy], replacing any earlier value. */
    suspend fun put(policy: CachePolicy, key: String, value: ByteArray) {
        when (policy) {
            is CachePolicy.ShortTtl -> synchronized(shortLived) {
                shortLived[key] = CacheEntry(value.copyOf(), clock.nowMs())
            }
            CachePolicy.Immutable -> {
                putStored(IMMUTABLE, key, value)
                evictImmutable()
            }
            CachePolicy.LastKnown -> putStored(LAST_KNOWN, key, value)
        }
    }

    private fun getShortLived(key: String, ttlMs: Long): CacheEntry? = synchronized(shortLived) {
        val entry = shortLived[key] ?: return null
        if (clock.nowMs() - entry.atMs >= ttlMs) {
            shortLived.remove(key)
            return null
        }
        CacheEntry(entry.value.copyOf(), entry.atMs)
    }

    private suspend fun getStored(policy: Int, key: String, touch: Boolean): CacheEntry? {
        val name = entryName(policy, key)
        val row = dao.get(name) ?: return null
        if (row.policy != policy) return null
        val value = try {
            Aead.open(keys.enc, aad(name, policy), row.v)
        } catch (e: GeneralSecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (touch) dao.touch(name, clock.nowMs())
        return CacheEntry(value, row.atMs)
    }

    private suspend fun putStored(policy: Int, key: String, value: ByteArray) {
        val name = entryName(policy, key)
        dao.put(KvRow(k = name, v = Aead.seal(keys.enc, aad(name, policy), value), policy = policy, atMs = clock.nowMs()))
    }

    /** Deletes the least recently used immutable entries until the stored bytes fit under the cap. */
    private suspend fun evictImmutable() {
        while (dao.totalBytes(IMMUTABLE) > immutableCapBytes) {
            val oldest = dao.oldestKey(IMMUTABLE) ?: return
            dao.delete(oldest)
        }
    }

    /** The HMAC of [policy] and [key], which is the name the row is stored under. */
    private fun entryName(policy: Int, key: String): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(keys.mac, HMAC)) }
        return mac.doFinal("$policy\u0000$key".toByteArray(Charsets.UTF_8))
    }

    private fun aad(name: ByteArray, policy: Int): ByteArray = AAD_PREFIX + name + policy.toByte()

    private companion object {
        const val IMMUTABLE = 0
        const val LAST_KNOWN = 1
        const val HMAC = "HmacSHA256"
        val AAD_PREFIX = "lumio-cache-v1".toByteArray(Charsets.UTF_8)
    }
}
