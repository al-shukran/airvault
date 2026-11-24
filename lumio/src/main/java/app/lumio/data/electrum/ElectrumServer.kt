package app.lumio.data.electrum

/**
 * How a server's TLS certificate is trusted. A certificate that a certificate authority signed is checked against the platform's authorities
 * and against the server's host name. A self-signed certificate is trusted by its key instead: [Pinned] names the SHA-256 of the certificate's
 * SubjectPublicKeyInfo, in lowercase hex, and any other certificate is refused, whatever it chains to.
 */
sealed interface Trust {
    /** The certificate chains to an authority that the platform trusts, and it names the server's host. */
    data object Authority : Trust

    /** The leaf certificate's public key hashes to [spkiSha256]. The host name is not checked, because the key is the identity. */
    class Pinned(val spkiSha256: String) : Trust
}

/**
 * One Electrum server. [maxBatch] is the most requests that one JSON-RPC batch may carry. A server that answers an empty reply to a larger batch
 * gets a smaller limit.
 */
class ElectrumServer(
    val host: String,
    val trust: Trust,
    val maxBatch: Int = 100,
    val port: Int = 50002,
)

/**
 * The public servers that Lumio reads Bitcoin from, in the order a pool tries them first. Each certificate was read on 2026-11-24. The
 * self-signed ones are pinned by key, and the others are checked against the platform's authorities. The pins are verified by the live test.
 * electrum1.bluewallet.io is not listed: on 2026-11-24 its Electrum ports refused connections, so its certificate could not be read.
 */
object ElectrumServers {
    val PUBLIC: List<ElectrumServer> = listOf(
        ElectrumServer("bitcoin.lu.ke", Trust.Pinned("83a7bcc8efa492d08f35b05bf1556bb261433d60d04685decf37e9883e32a0aa")),
        ElectrumServer("electrum.bitaroo.net", Trust.Pinned("019e704aa5ec037d1b82a2931423e78e2dc295b1a7c4f67b233d29f479443bcc")),
        ElectrumServer("electrum.acinq.co", Trust.Authority),
        ElectrumServer("fulcrum.sethforprivacy.com", Trust.Pinned("17c554c72f0fdb357bd197c6b8725a19819b6b80d7d7549355a640776d45e1a6")),
        ElectrumServer("electrum.blockstream.info", Trust.Authority, maxBatch = 20),
        ElectrumServer("electrum.emzy.de", Trust.Pinned("83636cf382d3c9ff566891e3704b4cfc34fcb90da0e16167a6c524c887e0d02c")),
    )
}
