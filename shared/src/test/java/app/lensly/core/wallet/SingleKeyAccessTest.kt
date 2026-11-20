package app.lensly.core.wallet

import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.core.model.Curve
import app.airvault.core.signer.Path
import app.lensly.core.evm.EvmFixtures
import app.lensly.core.evm.Secp256k1
import app.lensly.core.ur.PathStep
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class SingleKeyAccessTest {
    private val key = EvmFixtures.artKey
    private val keys = SingleKeyAccess(key, EvmSigner.singleKeyPath)

    @Test
    fun theHeldPathHasThePublicKeyOfTheKey() {
        val publicKey = keys.publicKey(Curve.SECP256K1, EvmSigner.singleKeyPath).bytes

        assertEquals(AccountAddresses.addressOfPrivateKey(key), AccountAddresses.addressOfKey(AccountXpub(publicKey, ByteArray(32), 0)))
    }

    @Test
    fun theEcdsaSignatureIsTheWeb3jSignatureOfTheKey() {
        val digest = ByteArray(32) { 0x42 }
        val expected = Secp256k1.sign(digest, key)

        val signed = keys.signEcdsaSecp256k1(EvmSigner.singleKeyPath, digest)

        assertContentEquals(expected.r, signed.r)
        assertContentEquals(expected.s, signed.s)
        assertEquals(expected.recoveryId, signed.recoveryId)
    }

    /** A Single Key has one account, so every other path is refused, whatever the curve call. */
    @Test
    fun aPathOtherThanTheHeldOneIsRefused() {
        val digest = ByteArray(32)
        for (text in listOf("m/44'/60'/1'/0/0", "m/44'/60'/0'/0/1", "m/44'/0'/0'/0/0")) {
            assertFailsWith<IllegalArgumentException>(text) { keys.publicKey(Curve.SECP256K1, path(text)) }
            assertFailsWith<IllegalArgumentException>(text) { keys.signEcdsaSecp256k1(path(text), digest) }
        }
    }

    @Test
    fun itHoldsNoOtherKeyAndNeverHandsOutItsRawKey() {
        assertFailsWith<UnsupportedOperationException> { keys.signSchnorrBip340(EvmSigner.singleKeyPath, ByteArray(32), null) }
        assertFailsWith<UnsupportedOperationException> { keys.signEd25519(Curve.ED25519_SLIP10, EvmSigner.singleKeyPath, ByteArray(0)) }
        assertFailsWith<UnsupportedOperationException> { keys.withPrivateKey(Curve.SECP256K1, EvmSigner.singleKeyPath) { it } }
        assertFailsWith<IllegalArgumentException> { keys.publicKey(Curve.ED25519_SLIP10, EvmSigner.singleKeyPath) }
    }

    @Test
    fun aKeyThatIsNotThirtyTwoBytesIsRefused() {
        assertFailsWith<IllegalArgumentException> { SingleKeyAccess(ByteArray(31), EvmSigner.singleKeyPath) }
    }

    private fun path(text: String): Path = Path(
        text.removePrefix("m/").split('/').map { level ->
            PathStep(index = level.removeSuffix("'").toInt(), hardened = level.endsWith("'"))
        },
    )
}
