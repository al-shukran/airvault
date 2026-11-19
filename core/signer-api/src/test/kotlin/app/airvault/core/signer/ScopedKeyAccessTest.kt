package app.airvault.core.signer

import app.airvault.core.model.Curve
import app.airvault.core.model.KeyScope
import app.airvault.core.model.PathTemplate
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScopedKeyAccessTest {
    private val scopes = listOf(
        KeyScope(Curve.ED25519_BIP32, listOf(PathTemplate("m/44'/283'/*'/0/0")), purpose = "test accounts"),
    )
    private val delegate = CountingKeys()
    private val keys = ScopedKeyAccess(scopes, delegate)

    @Test
    fun passesAPathUnderTheWildcardAndDeeperPaths() {
        keys.publicKey(Curve.ED25519_BIP32, path("m/44'/283'/7'/0/0"))
        keys.publicKey(Curve.ED25519_BIP32, path("m/44'/283'/7'/0/0/1"))

        assertEquals(2, delegate.calls)
    }

    @Test
    fun refusesEachPathJustOutsideThePrefixBeforeTheDelegateSeesIt() {
        val outside = listOf(
            "m/44'/284'/0'/0/0", // another coin type
            "m/45'/283'/0'/0/0", // another purpose
            "m/44'/283'/0/0/0", // unhardened account level
            "m/44'/283'/0'/1/0", // fixed level differs
            "m/44'/283'/0'/0'/0", // hardened level where the prefix has none
            "m/44'/283'/0'/0", // one level short of the prefix
        )
        for (text in outside) {
            assertFailsWith<KeyScopeViolation>(text) { keys.publicKey(Curve.ED25519_BIP32, path(text)) }
        }
        assertEquals(0, delegate.calls)
    }

    @Test
    fun refusesACurveNoScopeDeclares() {
        assertFailsWith<KeyScopeViolation> { keys.publicKey(Curve.SECP256K1, path("m/44'/283'/0'/0/0")) }
        assertFailsWith<KeyScopeViolation> { keys.publicKey(Curve.ED25519_SLIP10, path("m/44'/283'/0'/0/0")) }
        assertEquals(0, delegate.calls)
    }

    @Test
    fun checksEverySigningCallNotOnlyPublicKey() {
        val outside = path("m/44'/284'/0'/0/0")
        assertFailsWith<KeyScopeViolation> { keys.signEcdsaSecp256k1(outside, ByteArray(32)) }
        assertFailsWith<KeyScopeViolation> { keys.signSchnorrBip340(outside, ByteArray(32), null) }
        assertFailsWith<KeyScopeViolation> { keys.signEd25519(Curve.ED25519_BIP32, outside, ByteArray(0)) }
        assertFailsWith<KeyScopeViolation> { keys.withPrivateKey(Curve.ED25519_BIP32, outside) { it.size } }
        assertEquals(0, delegate.calls)
    }

    @Test
    fun printsAPathInTheBip32Notation() {
        assertEquals("m/44'/283'/0'/0/0", path("m/44'/283'/0'/0/0").toString())
    }

    private fun path(text: String): Path = Path(
        text.removePrefix("m/").split('/').map { level ->
            PathStep(index = level.removeSuffix("'").toInt(), hardened = level.endsWith("'"))
        },
    )
}

/** Counts the calls that get past the scope check. Each call returns a placeholder result. */
private class CountingKeys : KeyAccess {
    var calls = 0

    override fun publicKey(curve: Curve, path: Path): PublicKey {
        calls++
        return PublicKey(curve, ByteArray(32))
    }

    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature {
        calls++
        return EcdsaSignature(ByteArray(32), ByteArray(32), 0)
    }

    override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray {
        calls++
        return ByteArray(64)
    }

    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray {
        calls++
        return ByteArray(64)
    }

    override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T {
        calls++
        return block(ByteArray(32))
    }
}
