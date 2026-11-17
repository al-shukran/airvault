package app.lensly.app.export

import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.BulkCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The size of the version-6 pairing for three wallets: the realistic vault (four chains, the Bitcoin accounts the owner stores, and an Address
 * Book of twelve), the demo, and the worst case the UI allows (100 accounts on each App, all named). The sizes are of the envelope with the body
 * stored as it is, since the JVM tests cannot load the native Brotli encoder. Lensly keeps the Brotli body only when it is smaller, so each stored
 * size is an upper bound for the code Lensly writes. Each budget is the measured value plus about 5%.
 */
class PairingSizeTest {

    /** The realistic vault, as a user pairs it: the number that the study's before and after figures compare. */
    @Test
    fun realisticVaultSize() {
        val (bytes, frames) = measure("realistic vault", RealisticPairing.payload())
        assertTrue("the realistic vault is $bytes bytes", bytes <= REALISTIC_STORED_BYTES_LIMIT)
        assertTrue("the realistic vault is $frames frames", frames <= REALISTIC_FRAMES_LIMIT)
    }

    /** The demo wallet as the on-device golden test writes it. */
    @Test
    fun demoWalletSize() {
        val (bytes, frames) = measure("demo", DemoPairingGolden.payload())
        assertTrue("the demo is $bytes bytes", bytes <= DEMO_STORED_BYTES_LIMIT)
        assertTrue("the demo is $frames frames", frames <= DEMO_FRAMES_LIMIT)
    }

    /** The largest wallet the UI allows: 100 accounts per chain on every App, and 96 stored Bitcoin accounts, which is what Lumio keeps. */
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
        assertEquals(payload.apps.map { app -> app.keys.map { it.material.toList() } }, read.apps.map { app -> app.keys.map { it.material.toList() } })
        println("PairingSize $label: ${bytes.size} bytes stored as they are, $frames frames at ${PairingCodec.FRAGMENT_BYTES} bytes per frame")
        return bytes.size to frames
    }

    /** The worst case: every key is random, of the length its App writes, and every account carries a name, a label or a group. */
    private fun worstCase(): PairingPayload {
        val random = Random(WORST_SEED)
        val count = BulkCap.entries.maxOf { it.accounts }
        val hardened = 0x80000000L
        fun compressedKey() = byteArrayOf((if (random.nextBoolean()) 2 else 3).toByte()) + random.nextBytes(32)
        fun accounts(name: String, n: Int) = List(n) { i ->
            PairingAccount(key = i, name = "$name ${i + 1}", label = if (i % 4 == 0) "Cold" else null, group = if (i % 3 == 0) "Savings" else null)
        }
        // Bitcoin keys are sorted by Script Type and index, as Lensly writes them, so each Script Type is one run.
        val bitcoin = ScriptType.entries.flatMap { scriptType -> (0 until WORST_BITCOIN / ScriptType.entries.size).map { scriptType to it } }
        return PairingPayload(
            fingerprint = random.nextLong(0, 0xFFFFFFFFL),
            name = "Worst case wallet",
            autoNameBase = "Worst case wallet",
            apps = listOf(
                PairingApp(
                    app = "bitcoin",
                    keys = bitcoin.map { (scriptType, index) ->
                        PairingKey(
                            path = listOf(hardened + scriptType.purpose, hardened, hardened + index),
                            material = compressedKey(),
                            chainCode = random.nextBytes(32),
                        )
                    },
                    accounts = accounts("Bitcoin", bitcoin.size),
                ),
                PairingApp(
                    app = "ethereum",
                    keys = List(count) { i -> PairingKey(listOf(hardened + 44, hardened + 60, hardened + i, 0, 0), random.nextBytes(20)) },
                    accounts = accounts("Ethereum", count),
                ),
                PairingApp(
                    app = "solana",
                    keys = List(count) { i -> PairingKey(listOf(hardened + 44, hardened + 501, hardened + i), random.nextBytes(32)) },
                    accounts = accounts("Solana", count),
                ),
                PairingApp(
                    app = "algorand",
                    keys = List(count) { i -> PairingKey(listOf(hardened + 44, hardened + 283, hardened + i, 0, 0), random.nextBytes(32)) },
                    accounts = accounts("Algorand", count),
                ),
            ),
        )
    }

    private companion object {
        const val WORST_SEED = 20261118
        const val WORST_BITCOIN = 96

        // Measured with this build, body stored as it is (limits are the measured value plus about 5%).
        const val REALISTIC_STORED_BYTES_LIMIT = 1_630
        const val REALISTIC_FRAMES_LIMIT = 7
        const val DEMO_STORED_BYTES_LIMIT = 4_140
        const val DEMO_FRAMES_LIMIT = 15
        const val WORST_STORED_BYTES_LIMIT = 21_200
        const val WORST_FRAMES_LIMIT = 72
    }
}
