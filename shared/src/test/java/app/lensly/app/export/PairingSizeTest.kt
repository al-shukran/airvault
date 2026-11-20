package app.lensly.app.export

import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.core.model.AddressKind
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.BulkCap
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size of the version-7 pairing for three wallets: the realistic vault (four chains, the Bitcoin accounts the owner stores, and an Address
 * Book of twelve), the demo, and the worst case the UI allows (100 accounts on each chain, all named). The sizes are of the envelope with the body
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

    /** The largest wallet the UI allows: 100 accounts per chain, and 96 stored Bitcoin accounts, which is what Lumio keeps. */
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
        assertEquals(payload.accounts.map { it.name }, read.accounts.map { it.name })
        assertEquals(
            payload.accounts.map { it.export.publicKey.bytes.toList() },
            read.accounts.map { it.export.publicKey.bytes.toList() },
        )
        println("PairingSize $label: ${bytes.size} bytes stored as they are, $frames frames at ${PairingCodec.FRAGMENT_BYTES} bytes per frame")
        return bytes.size to frames
    }

    /** The worst case: every key is random, of the length its chain writes, and every account carries a name, a label or a group. */
    private fun worstCase(): PairingPayload {
        val random = Random(WORST_SEED)
        val count = BulkCap.entries.maxOf { it.accounts }
        val fingerprint = random.nextLong(0, 0xFFFFFFFFL)
        val signer = fingerprint.toInt()
        fun compressedKey() = byteArrayOf((if (random.nextBoolean()) 2 else 3).toByte()) + random.nextBytes(32)
        fun named(prefix: String, i: Int) = Triple("$prefix ${i + 1}", if (i % 4 == 0) "Cold" else null, if (i % 3 == 0) "Savings" else null)
        // Bitcoin keys are sorted by Script Type and index, as Lensly writes them, so each Script Type is one run.
        val bitcoin = ScriptType.entries.flatMap { scriptType -> (0 until WORST_BITCOIN / ScriptType.entries.size).map { scriptType to it } }
        val bitcoinAccounts = bitcoin.mapIndexed { i, (scriptType, index) ->
            val (naming, label, group) = named("Bitcoin", i)
            PairingAccount(
                export = AccountExport(
                    chain = bitcoinMainnet,
                    signer = SignerRef(signer, accountSteps(scriptType.purpose, 0, index)),
                    publicKey = PublicKey(Curve.SECP256K1, compressedKey()),
                    chainCode = random.nextBytes(32),
                    kind = AddressKind.DEFAULT,
                ),
                name = naming,
                label = label,
                group = group,
            )
        }
        val ethereum = List(count) { i ->
            val (naming, label, group) = named("Ethereum", i)
            PairingAccount(
                export = keyExport(ethereumMainnet, signer, accountSteps(44, 60, i, 0, 0), Curve.SECP256K1, compressedKey()),
                name = naming,
                label = label,
                group = group,
            )
        }
        val solana = List(count) { i ->
            val (naming, label, group) = named("Solana", i)
            PairingAccount(
                export = keyExport(SOLANA_MAINNET, signer, accountSteps(44, 501, i), Curve.ED25519_SLIP10, random.nextBytes(32)),
                name = naming,
                label = label,
                group = group,
            )
        }
        val algorand = List(count) { i ->
            val (naming, label, group) = named("Algorand", i)
            PairingAccount(
                export = keyExport(algorandMainnet, signer, accountSteps(44, 283, i, 0, 0), Curve.ED25519_BIP32, random.nextBytes(32)),
                name = naming,
                label = label,
                group = group,
            )
        }
        return PairingPayload(
            fingerprint = fingerprint,
            name = "Worst case wallet",
            autoNameBase = "Worst case wallet",
            accounts = ethereum + bitcoinAccounts + solana + algorand,
        )
    }

    private companion object {
        const val WORST_SEED = 20261118
        const val WORST_BITCOIN = 96

        // Measured with this build, body stored as it is (limits are the measured value plus about 5%).
        const val REALISTIC_STORED_BYTES_LIMIT = 1_900
        const val REALISTIC_FRAMES_LIMIT = 7
        const val DEMO_STORED_BYTES_LIMIT = 4_430
        const val DEMO_FRAMES_LIMIT = 15
        const val WORST_STORED_BYTES_LIMIT = 22_800
        const val WORST_FRAMES_LIMIT = 77
    }
}
