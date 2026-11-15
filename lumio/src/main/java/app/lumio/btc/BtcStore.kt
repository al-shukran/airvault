package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The Bitcoin data that Lumio keeps in its vault: the last scan of each account, and the Esplora servers the owner chose. Both are vault
 * records, not wallet records, so they are sealed under the vault key and erased with it. A record that cannot be read reads as empty:
 * a cache that is lost costs a scan, and a server setting that is lost falls back to the defaults.
 */
class BtcStore(private val session: VaultSession) {

    /** The cached scan of the account [key], or null when there is none, or the cache does not read. */
    fun snapshot(key: String): BtcSnapshot? = readSnapshots()[key]

    /** Stores [snapshot] in place of the earlier scan of its account. A vault that is closed keeps the old cache. */
    @Synchronized
    fun save(snapshot: BtcSnapshot): Boolean = try {
        val all = readSnapshots().toMutableMap()
        all[snapshot.key] = snapshot
        session.putVaultRecord(SCAN_RECORD, SnapshotCodec.encode(all.values))
        true
    } catch (e: IllegalStateException) {
        false
    }

    /** Drops the cached scans of accounts that are no longer paired, so a deleted wallet leaves nothing behind. */
    @Synchronized
    fun retain(keys: Set<String>) {
        val all = readSnapshots()
        if (all.keys.all { it in keys }) return
        try {
            session.putVaultRecord(SCAN_RECORD, SnapshotCodec.encode(all.values.filter { it.key in keys }))
        } catch (e: IllegalStateException) {
            // The vault is closed. The next open drops them.
        }
    }

    /** The owner's server overrides, by network. A network with no entry reads its default server. */
    fun servers(): Map<BtcNetwork, String> = try {
        session.getVaultRecord(SERVERS_RECORD)?.let { ServerCodec.decode(it) } ?: emptyMap()
    } catch (e: IllegalStateException) {
        emptyMap()
    }

    /** Stores [overrides] in place of the earlier ones. A network missing from [overrides] reads its default from now on. */
    @Synchronized
    fun saveServers(overrides: Map<BtcNetwork, String>) {
        session.putVaultRecord(SERVERS_RECORD, ServerCodec.encode(overrides))
    }

    private fun readSnapshots(): Map<String, BtcSnapshot> = try {
        session.getVaultRecord(SCAN_RECORD)?.let { SnapshotCodec.decode(it) } ?: emptyMap()
    } catch (e: IllegalStateException) {
        emptyMap()
    } catch (e: IOException) {
        emptyMap()
    } catch (e: IllegalArgumentException) {
        emptyMap()
    }

    private companion object {
        const val SCAN_RECORD = "btc-scan"
        const val SERVERS_RECORD = "btc-servers"
    }
}

/** The scan cache record: a version byte, the count, then each snapshot in full, in DataOutputStream style. */
private object SnapshotCodec {
    private const val VERSION = 1
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
                out.writeUTF(s.server)
                out.writeLong(s.scannedAtMs)
                out.writeLong(s.tipHeight)
                out.writeLong(s.confirmedSats)
                out.writeLong(s.unconfirmedSats)
                out.writeInt(s.nextReceiveIndex)
                out.writeUTF(s.nextReceiveAddress)
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
            val server = input.readUTF()
            val scannedAtMs = input.readLong()
            val tipHeight = input.readLong()
            val confirmedSats = input.readLong()
            val unconfirmedSats = input.readLong()
            val nextReceiveIndex = input.readInt()
            val nextReceiveAddress = input.readUTF()
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
            BtcSnapshot(
                key = key,
                identity = identity,
                server = server,
                scannedAtMs = scannedAtMs,
                tipHeight = tipHeight,
                confirmedSats = confirmedSats,
                unconfirmedSats = unconfirmedSats,
                nextReceiveIndex = nextReceiveIndex,
                nextReceiveAddress = nextReceiveAddress,
                utxos = utxos,
                transactions = txs,
            )
        }.associateBy { it.key }
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

/** The servers record: a version byte, the count, then each override as a network code and its base URL. */
private object ServerCodec {
    private const val VERSION = 1

    fun encode(overrides: Map<BtcNetwork, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeInt(overrides.size)
            for ((network, url) in overrides) {
                out.writeByte(network.ordinal)
                out.writeUTF(url)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<BtcNetwork, String> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readUnsignedByte() == VERSION) { "unknown servers version" }
        val count = input.readInt()
        require(count in 0..BtcNetwork.entries.size) { "server count out of range" }
        buildMap {
            repeat(count) {
                val network = BtcNetwork.entries.getOrNull(input.readUnsignedByte()) ?: throw IllegalArgumentException("unknown network")
                put(network, input.readUTF())
            }
        }
    }
}
