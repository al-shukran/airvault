package app.lensly.core.parse

import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep

/** The master fingerprint that [algorandRequestCbor] names. */
internal const val ALGORAND_TEST_FINGERPRINT = 0x12121212

/** The Ledger Algorand path, 44'/283'/0'/0/0, that [algorandRequestCbor] names. */
internal val ALGORAND_TEST_PATH = listOf(PathStep(44, true), PathStep(283, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))

/** An algo-sign-request on the path [ALGORAND_TEST_PATH] of [ALGORAND_TEST_FINGERPRINT], with the codec's fields for [signData] and [signType]. */
internal fun algorandRequestCbor(signData: ByteArray, signType: Long): ByteArray {
    val components = ALGORAND_TEST_PATH.flatMap { listOf(Cbor.Uint(it.index.toLong()), Cbor.Bool(it.hardened)) }
    val keypath = Cbor.Tagged(
        304,
        Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(ALGORAND_TEST_FINGERPRINT.toLong()))),
    )
    return Cbor.encode(
        Cbor.Dict(
            listOf(
                Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(ByteArray(16) { 0x11 })),
                Cbor.Uint(2L) to Cbor.Bytes(signData),
                Cbor.Uint(3L) to keypath,
                Cbor.Uint(4L) to Cbor.Text(""),
                Cbor.Uint(5L) to Cbor.Text("lumio"),
                Cbor.Uint(6L) to Cbor.Uint(signType),
            ),
        ),
    )
}
