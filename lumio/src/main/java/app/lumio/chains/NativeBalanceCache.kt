package app.lumio.chains

import app.lensly.core.vault.VaultSession
import app.lumio.data.net.NetworkId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One native balance that Lumio read for an address of a paired wallet on [network]. [amount] is the balance in the network's base unit (microAlgos or
 * lamports) as a decimal string, and [atMs] is when the read was made, in epoch milliseconds.
 */
@Serializable
data class CachedNativeBalance(
    val walletId: String,
    val network: NetworkId,
    val address: String,
    val amount: String,
    val atMs: Long,
) {
    /** The identity of a read: one wallet's address on one network. A later read with the same identity replaces this one. */
    val key: Triple<String, NetworkId, String>
        get() = Triple(walletId, network, address)
}

/**
 * The last native balance reads of every paired wallet, kept as an encrypted vault record, as the EVM balance cache is. A device that is offline shows
 * these reads with the time each was made, so it never shows a balance as current when it is not.
 */
object NativeBalanceCache {
    /** The vault record that holds the reads. */
    const val RECORD = "lumio-native-balance-cache"

    private val json = Json

    /** The cached reads, or none when nothing is stored or the record does not parse. */
    fun read(session: VaultSession): List<CachedNativeBalance> {
        val bytes = session.getVaultRecord(RECORD) ?: return emptyList()
        return try {
            json.decodeFromString(CacheFile.serializer(), bytes.decodeToString()).entries
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    fun write(session: VaultSession, entries: List<CachedNativeBalance>) {
        session.putVaultRecord(RECORD, json.encodeToString(CacheFile.serializer(), CacheFile(entries)).encodeToByteArray())
    }

    @Serializable
    private class CacheFile(val entries: List<CachedNativeBalance> = emptyList())
}
