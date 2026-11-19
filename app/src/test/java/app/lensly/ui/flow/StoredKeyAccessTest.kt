package app.lensly.ui.flow

import app.airvault.core.model.Curve
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.lensly.core.ur.PathStep
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The key access of a Solana request: a signature is made only with the key the wallet stores for the reviewed account. */
class StoredKeyAccessTest {
    private val path = Path(listOf(PathStep(44, hardened = true), PathStep(501, hardened = true), PathStep(0, hardened = true)))
    private val stored = ByteArray(32) { 1 }
    private val other = ByteArray(32) { 2 }

    @Test
    fun aSignatureIsMadeWhenTheKeyIsTheStoredKey() {
        val keys = FakeKeys(stored)

        val signature = StoredKeyAccess(keys, stored).signEd25519(Curve.ED25519_SLIP10, path, byteArrayOf(9))

        assertContentEquals(byteArrayOf(9), signature)
        assertEquals(1, keys.signed)
    }

    @Test
    fun aSignatureIsRefusedWhenTheKeyIsNotTheStoredKey() {
        val keys = FakeKeys(other)

        val refused = assertFailsWith<IllegalArgumentException> {
            StoredKeyAccess(keys, stored).signEd25519(Curve.ED25519_SLIP10, path, byteArrayOf(9))
        }

        assertEquals("Key does not match account", refused.message)
        assertEquals(0, keys.signed)
    }

    @Test
    fun thePublicKeyIsTheOneTheKeyAccessGives() {
        val access = StoredKeyAccess(FakeKeys(other), stored)

        assertContentEquals(other, access.publicKey(Curve.ED25519_SLIP10, path).bytes)
    }

    /** Answers with the key [key], echoes each message as its signature, and counts the signatures it makes. */
    private class FakeKeys(private val key: ByteArray) : KeyAccess {
        var signed = 0

        override fun publicKey(curve: Curve, path: Path): PublicKey = PublicKey(curve, key)

        override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature = error("not used")

        override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray = error("not used")

        override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray {
            signed++
            return message
        }

        override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T = error("not used")
    }
}
