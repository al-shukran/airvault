package app.lensly.app.export

import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.wallet.BulkCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The size of the version-5 pairing for the demo wallet and for the worst case the UI allows: 100 accounts on each of the four Apps, the
 * largest Bulk Cap (Lumio refuses more than 100 Ethereum accounts). The sizes are of the envelope with the body stored as it is, since the
 * JVM tests cannot load the native Brotli encoder. Lensly keeps the Brotli body only when it is smaller, so each stored size is an upper bound
 * for the code Lensly writes. The Brotli size of the demo is measured by Lumio's PairDemoBrotliGoldenTest.
 *
 * Measured with this build, body stored as it is (limits are the measured value plus about 5%):
 *  - demo: 5,428 bytes, 28 frames. The Brotli-compressed demo is 4,318 bytes, 22 frames (PairDemoBrotliGoldenTest in Lumio). The demo holds
 *    Bitcoin and Ethereum accounts only, so it does not exercise the Solana or Algorand keys.
 *  - worst case: 46,698 bytes, 234 frames. Brotli cannot shrink the random parts of its keys: 400 keys of 32 random public-key bytes and
 *    32 random chain-code bytes, plus 200 random parent fingerprints, is at least 26,400 bytes, so the Brotli-compressed worst case is at
 *    least 132 frames and at most 234.
 * The worst case keys are seeded random bytes in the layout of real keys, so the numbers are repeatable.
 */
class PairingSizeTest {

    /** The demo wallet as the on-device golden test writes it. */
    @Test
    fun demoWalletSize() {
        val (bytes, frames) = measure("demo", DemoPairingGolden.payload())
        assertTrue("the demo is $bytes bytes", bytes <= DEMO_STORED_BYTES_LIMIT)
        assertTrue("the demo is $frames frames", frames <= DEMO_FRAMES_LIMIT)
    }

    /** The largest wallet the UI allows: 100 accounts per chain on every App. */
    @Test
    fun worstCaseWalletSize() {
        val (bytes, frames) = measure("worst case", worstCase())
        assertTrue("the worst case is $bytes bytes", bytes <= WORST_STORED_BYTES_LIMIT)
        assertTrue("the worst case is $frames frames", frames <= WORST_FRAMES_LIMIT)
    }

    /** Encodes [payload] without Brotli, reads it back, and returns its envelope size and frame count. */
    private fun measure(label: String, payload: PairingPayload): Pair<Int, Int> {
        val bytes = PairingCodec.encode(payload, brotli = null)
        val frames = PairingCodec.urParts(payload, brotli = null).size
        val read = PairingCodec.decode(bytes, brotli = null)
        assertEquals(payload.apps.map { it.accounts }, read.apps.map { it.accounts })
        assertEquals(payload.apps.map { app -> app.keys.map { it.publicKey.toList() } }, read.apps.map { app -> app.keys.map { it.publicKey.toList() } })
        println("PairingSize $label: ${bytes.size} bytes stored as they are, $frames frames at ${DEFAULT_MAX_FRAGMENT} bytes per frame")
        return bytes.size to frames
    }

    private fun worstCase(): PairingPayload {
        val random = Random(WORST_SEED)
        val count = BulkCap.entries.maxOf { it.accounts }
        val hardened = 0x80000000L
        fun compressedKey() = byteArrayOf((if (random.nextBoolean()) 2 else 3).toByte()) + random.nextBytes(32)
        fun ed25519Key() = byteArrayOf(0) + random.nextBytes(32)
        fun key(path: List<Long>, publicKey: ByteArray, parent: Long) = PairingKey(
            path = path,
            depth = path.size,
            parentFingerprint = parent,
            publicKey = publicKey,
            chainCode = random.nextBytes(32),
        )
        fun accounts(name: String) = List(count) { i ->
            PairingAccount(key = i, name = "$name ${i + 1}", label = if (i % 4 == 0) "Cold" else null, group = if (i % 3 == 0) "Savings" else null)
        }
        return PairingPayload(
            fingerprint = random.nextLong(0, 0xFFFFFFFFL),
            name = "Worst case wallet",
            apps = listOf(
                PairingApp(
                    app = "bitcoin",
                    keys = List(count) { i -> key(listOf(hardened + 84, hardened, hardened + i), compressedKey(), random.nextLong(0, 0xFFFFFFFFL)) },
                    accounts = accounts("Bitcoin"),
                ),
                PairingApp(
                    app = "ethereum",
                    keys = List(count) { i -> key(listOf(hardened + 44, hardened + 60, hardened + i, 0, 0), compressedKey(), random.nextLong(0, 0xFFFFFFFFL)) },
                    accounts = accounts("Ethereum"),
                ),
                PairingApp(
                    app = "solana",
                    keys = List(count) { i -> key(listOf(hardened + 44, hardened + 501, hardened + i), ed25519Key(), 0L) },
                    accounts = accounts("Solana"),
                ),
                PairingApp(
                    app = "algorand",
                    keys = List(count) { i -> key(listOf(hardened + 44, hardened + 283, hardened + i, 0, 0), ed25519Key(), 0L) },
                    accounts = accounts("Algorand"),
                ),
            ),
        )
    }

    private companion object {
        const val WORST_SEED = 20261117

        // Measured 5,428 bytes and 28 frames for the demo, and 46,698 bytes and 234 frames for the worst case, stored as they are.
        const val DEMO_STORED_BYTES_LIMIT = 5_700
        const val DEMO_FRAMES_LIMIT = 29
        const val WORST_STORED_BYTES_LIMIT = 49_000
        const val WORST_FRAMES_LIMIT = 245
    }
}
