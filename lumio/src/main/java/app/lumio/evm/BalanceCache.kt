package app.lumio.evm

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One native balance that Lumio read for an address on a chain. [wei] is the balance in wei, as a decimal string, and [atMs]
 * is when the read was made, in epoch milliseconds. The address is one of the paired wallet [walletId].
 */
@Serializable
data class CachedBalance(
    val walletId: String,
    val chainId: Long,
    val address: String,
    val wei: String,
    val atMs: Long,
) {
    /** The identity of a read: one wallet's address on one chain. A later read with the same identity replaces this one. */
    val key: Triple<String, Long, String>
        get() = Triple(walletId, chainId, address)
}

/**
 * The last reads of every paired wallet, kept as an encrypted vault record. A device that is offline shows these reads with the time each was
 * made, so it never shows a balance as current when it is not.
 */
object BalanceCache {
    /** The vault record that holds the reads. */
    const val RECORD = "lumio-balance-cache"

    private val json = Json

    /** The cached reads, or none when nothing is stored or the record does not parse. */
    fun read(session: VaultSession): List<CachedBalance> {
        val bytes = session.getVaultRecord(RECORD) ?: return emptyList()
        return try {
            json.decodeFromString(CacheFile.serializer(), bytes.decodeToString()).entries
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    fun write(session: VaultSession, entries: List<CachedBalance>) {
        session.putVaultRecord(RECORD, json.encodeToString(CacheFile.serializer(), CacheFile(entries)).encodeToByteArray())
    }

    @Serializable
    private class CacheFile(val entries: List<CachedBalance> = emptyList())
}
