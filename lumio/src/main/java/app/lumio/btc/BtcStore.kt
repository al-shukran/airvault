package app.lumio.btc

import app.lumio.data.cache.Cache
import app.lumio.data.cache.CachePolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The last known balance of each account: its last scan, kept in the Lumio cache under the last-known policy. It stays until a newer scan replaces
 * it. A screen reads it at once when it opens, so a balance shows before any server answers.
 */
class BtcStore(private val cache: Cache) {

    /** The last scan of the account [key], or null when there is none, or the stored copy does not read. */
    suspend fun snapshot(key: String): BtcSnapshot? = try {
        cache.get(CachePolicy.LastKnown, nameOf(key))?.let { SnapshotCodec.decode(it.value).values.firstOrNull() }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Stores [snapshot] in place of the earlier scan of its account. */
    suspend fun save(snapshot: BtcSnapshot) {
        cache.put(CachePolicy.LastKnown, nameOf(snapshot.key), SnapshotCodec.encode(listOf(snapshot)))
    }

    /** Drops the stored scans of accounts that are no longer paired, so a deleted wallet leaves no balance behind. [keys] are the paired accounts. */
    suspend fun retain(keys: Set<String>) {
        cache.retainStored(CachePolicy.LastKnown) { bytes ->
            try {
                SnapshotCodec.decode(bytes).keys.all { it in keys }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }

    private fun nameOf(key: String): String = "btc-scan/$key"
}

/** The snapshot record: a version byte, the count, then each snapshot in full, in DataOutputStream style. */
private object SnapshotCodec {
    private const val VERSION = 5
    private const val MAX_ENTRIES = 256
    private const val MAX_LIST = 100_000

    fun encode(snapshots: Collection<BtcSnapshot>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeInt(snapshots.size)
            for (s in snapshots) {
                out.writeUTF(s.key)
                out.writeUTF(s.identity)
                out.writeLong(s.scannedAtMs)
                out.writeLong(s.tipHeight)
                out.writeLong(s.confirmedSats)
                out.writeLong(s.unconfirmedSats)
                out.writeInt(s.nextReceiveIndex)
                out.writeInt(s.utxos.size)
                for (u in s.utxos) {
                    out.writeUTF(u.txid)
                    out.writeInt(u.vout)
                    out.writeLong(u.valueSats)
                    writeOptionalLong(out, u.blockHeight)
                }
                out.writeInt(s.transactions.size)
                for (t in s.transactions) {
                    out.writeUTF(t.txid)
                    out.writeLong(t.netSats)
                    writeOptionalLong(out, t.blockHeight)
                    writeOptionalLong(out, t.blockTimeSec)
                }
                out.writeInt(s.addresses.size)
                for (address in s.addresses) out.writeUTF(address)
                out.writeBoolean(s.complete)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<String, BtcSnapshot> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readUnsignedByte() == VERSION) { "unknown scan cache version" }
        val count = input.readInt()
        require(count in 0..MAX_ENTRIES) { "scan cache count out of range" }
        List(count) {
            val key = input.readUTF()
            val identity = input.readUTF()
            val scannedAtMs = input.readLong()
            val tipHeight = input.readLong()
            val confirmedSats = input.readLong()
            val unconfirmedSats = input.readLong()
            val nextReceiveIndex = input.readInt()
            val utxoCount = input.readInt()
            require(utxoCount in 0..MAX_LIST) { "utxo count out of range" }
            val utxos = List(utxoCount) {
                BtcUtxo(input.readUTF(), input.readInt(), input.readLong(), readOptionalLong(input))
            }
            val txCount = input.readInt()
            require(txCount in 0..MAX_LIST) { "transaction count out of range" }
            val txs = List(txCount) {
                BtcTx(input.readUTF(), input.readLong(), readOptionalLong(input), readOptionalLong(input))
            }
            val addresses = readAddresses(input)
            BtcSnapshot(
                key = key,
                identity = identity,
                scannedAtMs = scannedAtMs,
                tipHeight = tipHeight,
                confirmedSats = confirmedSats,
                unconfirmedSats = unconfirmedSats,
                nextReceiveIndex = nextReceiveIndex,
                utxos = utxos,
                transactions = txs,
                addresses = addresses,
                complete = input.readBoolean(),
            )
        }.associateBy { it.key }
    }

    private fun readAddresses(input: DataInputStream): List<String> {
        val count = input.readInt()
        require(count in 0..MAX_LIST) { "address count out of range" }
        return List(count) { input.readUTF() }
    }

    private fun writeOptionalLong(out: DataOutputStream, value: Long?) {
        out.writeBoolean(value != null)
        out.writeLong(value ?: 0L)
    }

    private fun readOptionalLong(input: DataInputStream): Long? {
        val present = input.readBoolean()
        val value = input.readLong()
        return if (present) value else null
    }
}
