package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * ur:eth-sign-request, the writing side of [EthSignRequest]. It uses the keys and tags that [EthSignRequest.decode] reads. Every
 * request it writes is decoded again before it is returned, so a request that Lensly would refuse is never encoded.
 */
object EthSignRequestEncoder {
    /**
     * Frames for [request]. One frame is a single code, and more are the parts of an animated one: take them with
     * app.lensly.app.export.partsOf, as the signing screens do. [maxFragment] is the largest CBOR fragment per frame.
     */
    fun encode(request: EthSignRequest, maxFragment: Int = DEFAULT_MAX_FRAGMENT): Sequence<String> {
        val cbor = Cbor.encode(payloadOf(request))
        when (val check = EthSignRequest.decode(cbor)) {
            is EthSignRequest.Result.Ok -> Unit
            is EthSignRequest.Result.Rejected -> throw IllegalArgumentException("Lensly would refuse this request: ${check.reason}")
        }
        return framesOf(UR(EthSignRequest.UR_TYPE, cbor), maxFragment)
    }

    private fun payloadOf(request: EthSignRequest): Cbor.Dict {
        val fields = ArrayList<Pair<Cbor, Cbor>>()
        fun put(key: Long, value: Cbor?) {
            if (value != null) fields += Cbor.Uint(key) to value
        }
        put(1L, request.requestId?.let { Cbor.Tagged(UUID_TAG, Cbor.Bytes(it)) })
        put(2L, Cbor.Bytes(request.signData))
        put(3L, Cbor.Uint(request.dataType.wire.toLong()))
        put(4L, request.chainId?.let { Cbor.Uint(it) })
        put(5L, keyPathOf(request))
        put(6L, request.address?.let { Cbor.Bytes(it) })
        put(7L, request.origin?.let { Cbor.Text(it) })
        return Cbor.Dict(fields)
    }

    /** The key path as the decoder reads it: a tag 304 map with the index steps and, when known, the source fingerprint. */
    private fun keyPathOf(request: EthSignRequest): Cbor {
        val components = request.derivationPath.flatMap { step -> listOf(Cbor.Uint(step.index.toLong()), Cbor.Bool(step.hardened)) }
        val fields = ArrayList<Pair<Cbor, Cbor>>()
        fields += Cbor.Uint(1L) to Cbor.Arr(components)
        request.sourceFingerprint?.let { fields += Cbor.Uint(2L) to Cbor.Uint(it.toLong() and 0xFFFFFFFFL) }
        return Cbor.Tagged(KEYPATH_TAG, Cbor.Dict(fields))
    }
}
