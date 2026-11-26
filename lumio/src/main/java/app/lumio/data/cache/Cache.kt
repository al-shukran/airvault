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

/**
 * The largest value the cache takes, in bytes. A database row is read through a cursor window of about 2 MB, and a larger row cannot be read back, so
 * a value over this is refused before it is stored.
 */
const val MAX_VALUE_BYTES = 1024 * 1024

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

/**
 * A result's cache key and policy, with the codec for its type. The scheduler reads and fills the cache with it. [keep] says whether a fresh result
 * is stored at all: a confirmed transaction is kept for good only once it has enough confirmations, for example.
 */
class CacheUse<T>(val policy: CachePolicy, val key: String, val codec: Codec<T>, val keep: (T) -> Boolean = { true })

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

    /**
     * Stores [value] under [key] for [policy], replacing any earlier value.
     * @throws IllegalArgumentException when [value] is over [MAX_VALUE_BYTES], so a row too large to read back is never written.
     */
    suspend fun put(policy: CachePolicy, key: String, value: ByteArray) {
        require(value.size <= MAX_VALUE_BYTES) { "A cached value is at most $MAX_VALUE_BYTES bytes; $key has ${value.size}." }
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

    /** Deletes the value under [key] for [policy], if there is one. */
    suspend fun delete(policy: CachePolicy, key: String) {
        when (policy) {
            is CachePolicy.ShortTtl -> synchronized(shortLived) { shortLived.remove(key) }
            CachePolicy.Immutable -> dao.delete(entryName(IMMUTABLE, key))
            CachePolicy.LastKnown -> dao.delete(entryName(LAST_KNOWN, key))
        }
    }

    /**
     * Deletes each stored entry of [policy] whose value [keep] rejects, and each one that does not open. The owner's data for a wallet that is no
     * longer paired is removed this way: its balances are kept only while [keep] accepts them. Values with a short time to live are not stored.
     */
    suspend fun retainStored(policy: CachePolicy, keep: (ByteArray) -> Boolean) {
        val code = when (policy) {
            is CachePolicy.ShortTtl -> return
            CachePolicy.Immutable -> IMMUTABLE
            CachePolicy.LastKnown -> LAST_KNOWN
        }
        for (row in dao.all()) {
            if (row.policy != code) continue
            val value = try {
                Aead.open(keys.enc, aad(row.k, row.policy), row.v)
            } catch (e: GeneralSecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (value == null || !keep(value)) dao.delete(row.k)
        }
    }

    /**
     * Deletes every stored entry that does not open under [keys]: one sealed under an older key, or one that a damaged file left unreadable. Run once
     * when the vault opens, so the database never keeps bytes that no session can read.
     */
    suspend fun purgeUnreadable() {
        for (row in dao.all()) if (!opens(row)) dao.delete(row.k)
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

    /**
     * Deletes the least recently used immutable entries that open under [keys] until the stored bytes fit under the cap. The database is shared with
     * the other vault, whose entries this session cannot read, so it never deletes one: a Decoy Vault must not cost the real vault its cache. Those
     * entries go when the real vault next opens ([purgeUnreadable]). It looks at no more than [MAX_FOREIGN] of them per call; past that the cache may
     * stay over its cap in a Decoy Vault session, which is cheaper than walking every row of the real vault.
     */
    private suspend fun evictImmutable() {
        var total = dao.totalBytes(IMMUTABLE)
        var foreign = 0
        while (total > immutableCapBytes) {
            // Every row before the offset is foreign: the ones that opened were deleted.
            val page = dao.oldestKeys(IMMUTABLE, MAX_FOREIGN, foreign)
            if (page.isEmpty()) return
            for (name in page) {
                val row = dao.get(name)
                if (row == null || opens(row)) {
                    dao.delete(name)
                    total -= row?.v?.size ?: 0
                    if (total <= immutableCapBytes) return
                } else if (++foreign >= MAX_FOREIGN) {
                    return
                }
            }
        }
    }

    private fun opens(row: KvRow): Boolean = try {
        Aead.open(keys.enc, aad(row.k, row.policy), row.v)
        true
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
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
        const val MAX_FOREIGN = 256
        const val HMAC = "HmacSHA256"
        val AAD_PREFIX = "lumio-cache-v1".toByteArray(Charsets.UTF_8)
    }
}
