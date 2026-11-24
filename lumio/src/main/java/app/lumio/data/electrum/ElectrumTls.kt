package app.lumio.data.electrum

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** The socket factory that checks a server's certificate the way [trust] says. Each connection gets one, so no trust is shared between servers. */
internal fun tlsSocketFactory(trust: Trust): SSLSocketFactory {
    val managers: Array<TrustManager>? = when (trust) {
        Trust.Authority -> null
        is Trust.Pinned -> arrayOf<TrustManager>(PinnedKey(trust.spkiSha256))
    }
    return SSLContext.getInstance("TLS").apply { init(null, managers, null) }.socketFactory
}

/** Accepts only a leaf certificate whose public key hashes to [spkiSha256]. */
private class PinnedKey(private val spkiSha256: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("the server sent no certificate")
        val key = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded).toLowerHex()
        if (key != spkiSha256) throw CertificateException("the server's key is not the pinned key")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("the client presents no certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
