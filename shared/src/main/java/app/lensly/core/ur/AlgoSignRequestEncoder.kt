package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * ur:algo-sign-request, the writing side of [AlgoSignRequest]. Lumio builds these and Lensly reads them with
 * [AlgoSignRequest.decode]. Every request is decoded again before it is returned, so a request that Lensly would refuse is
 * never encoded.
 */
object AlgoSignRequestEncoder {
    /**
     * Frames for [request]. One frame is a single code, and more are the parts of an animated one, taken with
     * app.lensly.app.export.partsOf. [maxFragment] is the largest CBOR fragment per frame.
     */
    fun encode(request: AlgoSignRequest, maxFragment: Int = DEFAULT_MAX_FRAGMENT): Sequence<String> {
        val cbor = Cbor.encode(payloadOf(request))
        when (val check = AlgoSignRequest.decode(cbor)) {
            is AlgoSignRequest.Result.Ok -> Unit
            is AlgoSignRequest.Result.Rejected -> throw IllegalArgumentException("Lensly would refuse this request: ${check.reason}")
        }
        return framesOf(UR(AlgoSignRequest.UR_TYPE, cbor), maxFragment)
    }

    private fun payloadOf(request: AlgoSignRequest): Cbor.Dict = Cbor.Dict(
        listOf(
            Cbor.Uint(1L) to Cbor.Tagged(EthSignRequest.UUID_TAG, Cbor.Bytes(request.requestId)),
            Cbor.Uint(2L) to Cbor.Bytes(request.signData),
            Cbor.Uint(3L) to keyPathOf(request),
            Cbor.Uint(4L) to Cbor.Text(request.address),
            Cbor.Uint(5L) to Cbor.Text(request.origin),
            Cbor.Uint(6L) to Cbor.Uint(request.signType.wire.toLong()),
        ),
    )

    /** The key path as the decoder reads it: a tag 304 map with the index steps and, when known, the source fingerprint. */
    private fun keyPathOf(request: AlgoSignRequest): Cbor {
        val components = request.derivationPath.flatMap { step -> listOf(Cbor.Uint(step.index.toLong()), Cbor.Bool(step.hardened)) }
        val fields = ArrayList<Pair<Cbor, Cbor>>()
        fields += Cbor.Uint(1L) to Cbor.Arr(components)
        request.sourceFingerprint?.let { fields += Cbor.Uint(2L) to Cbor.Uint(it.toLong() and 0xFFFFFFFFL) }
        return Cbor.Tagged(EthSignRequest.KEYPATH_TAG, Cbor.Dict(fields))
    }
}
