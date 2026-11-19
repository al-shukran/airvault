package app.lensly.core.ur

import app.airvault.chains.solana.signer.SolSignRequest
import com.sparrowwallet.hummingbird.UR

/**
 * ur:sol-sign-request, the writing side of [SolSignRequest]. It uses the keys and tags that [SolSignRequest.decode] reads. Every request it writes
 * is decoded again before it is returned, so a request that Lensly would refuse is never encoded.
 */
object SolSignRequestEncoder {
    /**
     * Frames for [request]: one frame is a single code, and more are the parts of an animated one. [maxFragment] is the largest CBOR fragment per
     * frame. Throws IllegalArgumentException when Lensly would refuse the request.
     */
    fun encode(request: SolSignRequest, maxFragment: Int = DEFAULT_MAX_FRAGMENT): Sequence<String> {
        val cbor = Cbor.encode(payloadOf(request))
        when (val check = SolSignRequest.decode(cbor)) {
            is SolSignRequest.Result.Ok -> Unit
            is SolSignRequest.Result.Rejected -> throw IllegalArgumentException("Lensly would refuse this request: ${check.reason}")
        }
        return framesOf(UR(SolSignRequest.UR_TYPE, cbor), maxFragment)
    }

    private fun payloadOf(request: SolSignRequest): Cbor.Dict {
        val fields = ArrayList<Pair<Cbor, Cbor>>()
        fun put(key: Long, value: Cbor?) {
            if (value != null) fields += Cbor.Uint(key) to value
        }
        put(1L, request.requestId?.let { Cbor.Tagged(UUID_TAG, Cbor.Bytes(it)) })
        put(2L, Cbor.Bytes(request.signData))
        put(3L, keyPathOf(request))
        put(4L, request.address?.let { Cbor.Bytes(it) })
        put(5L, request.origin?.let { Cbor.Text(it) })
        put(6L, Cbor.Uint(request.signType.wire.toLong()))
        put(7L, request.cluster?.let { Cbor.Text(it.wire) })
        return Cbor.Dict(fields)
    }

    /** The derivation path as the decoder reads it: a tag 304 map of index steps, and the source fingerprint when it is known. */
    private fun keyPathOf(request: SolSignRequest): Cbor {
        val components = request.derivationPath.flatMap { step -> listOf(Cbor.Uint(step.index.toLong()), Cbor.Bool(step.hardened)) }
        val fields = ArrayList<Pair<Cbor, Cbor>>()
        fields += Cbor.Uint(1L) to Cbor.Arr(components)
        request.sourceFingerprint?.let { fields += Cbor.Uint(2L) to Cbor.Uint(it.toLong() and 0xFFFFFFFFL) }
        return Cbor.Tagged(KEYPATH_TAG, Cbor.Dict(fields))
    }
}
