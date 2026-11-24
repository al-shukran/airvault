package app.lumio.data.electrum

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * A throwaway self-signed TLS identity for a fake server: its certificate's public key, hashed for a [Trust.Pinned], and the socket factory that
 * serves it. The JDK's keytool makes the key, so the tests need no library beyond the JDK.
 */
internal class TestIdentity private constructor(keyStore: KeyStore) {
    val spkiSha256: String = MessageDigest.getInstance("SHA-256")
        .digest(keyStore.getCertificate(ALIAS).publicKey.encoded)
        .toLowerHex()

    val serverSocketFactory: SSLServerSocketFactory = run {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(keyStore, PASSWORD)
        val context = SSLContext.getInstance("TLS")
        context.init(keys.keyManagers, null, null)
        context.serverSocketFactory
    }

    companion object {
        private const val ALIAS = "server"
        private val PASSWORD = "test-only".toCharArray()

        /** A new identity: an EC P-256 key and a certificate for localhost, made by the keytool of the running JDK. */
        fun generate(): TestIdentity {
            val dir = Files.createTempDirectory("electrum-test")
            try {
                val store = dir.resolve("identity.p12").toFile()
                val keytool = File(System.getProperty("java.home"), "bin/keytool").path
                val process = ProcessBuilder(
                    keytool, "-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA", "-dname", "CN=localhost", "-validity", "3650",
                    "-storetype", "PKCS12", "-keystore", store.path,
                    "-storepass", String(PASSWORD), "-keypass", String(PASSWORD),
                ).redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "keytool failed: $log" }
                val keyStore = KeyStore.getInstance("PKCS12")
                store.inputStream().use { keyStore.load(it, PASSWORD) }
                return TestIdentity(keyStore)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}

/** The identities that the tests share. Each one takes a keytool run, so each is made once. */
internal object TestIdentities {
    val server: TestIdentity by lazy { TestIdentity.generate() }
    val other: TestIdentity by lazy { TestIdentity.generate() }
}
