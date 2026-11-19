package app.lensly.ui.flow

import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Solana requests of the sign flow tests: the sol-sign-request a wallet sends, the transaction it carries, and the sol-signature the app
 * answers with. The sign data is built here byte for byte, so a test reads what it signs.
 */
internal object SolanaRequestFixtures {
    /** The System Program, which is the all-zero address. */
    const val SYSTEM_ADDRESS = "11111111111111111111111111111111"

    /** The requestId a wallet sends, which the response echoes. */
    val REQUEST_ID = ByteArray(16) { 0x11 }

    /** A legacy transfer of 1000 lamports from [payer] to a stranger. [payer] is the one signer, so the account it is the key of signs it. */
    fun legacyTransfer(payer: ByteArray): ByteArray {
        val keys = listOf(payer, ByteArray(32) { 9 }, Base58.decode(SYSTEM_ADDRESS))
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(1_000L).array()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 1))
        out.write(keys.size)
        keys.forEach { out.write(it) }
        out.write(ByteArray(32) { 7 })
        out.write(byteArrayOf(1, 2, 2, 0, 1, 12))
        out.write(data)
        return out.toByteArray()
    }

    /**
     * A sol-sign-request for [signData] on the account at [path], from the wallet with [fingerprint]. Pass null for [requestId] to leave it out.
     */
    fun requestCbor(
        signData: ByteArray,
        fingerprint: Int,
        path: List<Pair<Long, Boolean>> = listOf(44L to true, 501L to true, 0L to true),
        requestId: ByteArray? = REQUEST_ID,
    ): ByteArray {
        val components = path.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }
        val keypath = Cbor.Tagged(
            304,
            Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(fingerprint.toLong()))),
        )
        val fields = listOfNotNull(
            requestId?.let { Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(it)) },
            Cbor.Uint(2L) to Cbor.Bytes(signData),
            Cbor.Uint(3L) to keypath,
            Cbor.Uint(5L) to Cbor.Text("solflare"),
            Cbor.Uint(6L) to Cbor.Uint(1L),
        )
        return Cbor.encode(Cbor.Dict(fields))
    }

    /** The one frame of a request that fits in one QR code. */
    fun partOf(cbor: ByteArray): String = framesOf(UR("sol-sign-request", cbor), maxFragment = 4096).first()

    /** The 64-byte signature inside a single `ur:sol-signature` frame. */
    fun signatureOf(parts: List<String>): ByteArray {
        val scanner = UrScanner()
        assertTrue(scanner.receive(parts.single()))
        val ur = assertNotNull(scanner.result())
        assertEquals("sol-signature", ur.type)
        val fields = (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (key, value) ->
            (key as Cbor.Uint).value to value
        }
        return (fields.getValue(2L) as Cbor.Bytes).value
    }
}
