package app.lensly.core.wallet

import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.Curve
import app.airvault.core.signer.KeyScopeViolation
import app.airvault.core.signer.Path
import app.airvault.core.signer.ScopedKeyAccess
import app.lensly.core.algorand.LedgerEd25519
import app.lensly.core.ur.PathStep
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletKeyAccessTest {
    private val seed = Mnemonic.toSeed(MNEMONIC_A)

    @Test
    fun theAccountZeroPublicKeyIsTheLedgerVector() {
        val key = WalletKeyAccess(seed).publicKey(Curve.ED25519_BIP32, WalletKeyAccess.algorandPath(0))

        assertEquals(ACCOUNT_0_PUBLIC_KEY, key.bytes.toHex())
    }

    @Test
    fun theSignatureIsTheSignatureOfTheAccountsLedgerKey() {
        val message = "TX".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 3)
        val key = LedgerEd25519.algorandAccount(seed, 1)
        val expected = try {
            Ed25519Signer.sign(key.kL, message)
        } finally {
            key.wipe()
        }

        val signed = WalletKeyAccess(seed).signEd25519(Curve.ED25519_BIP32, WalletKeyAccess.algorandPath(1), message)

        assertContentEquals(expected, signed)
    }

    @Test
    fun aPathThatIsNotAnAlgorandAccountOrACurveThatIsNotHeldIsRefused() {
        val keys = WalletKeyAccess(seed)

        assertFailsWith<IllegalArgumentException> { keys.publicKey(Curve.ED25519_BIP32, path("m/44'/283'/0'/0/1")) }
        assertFailsWith<IllegalArgumentException> { keys.publicKey(Curve.ED25519_BIP32, path("m/44'/0'/0'/0/0")) }
        assertFailsWith<IllegalArgumentException> { keys.publicKey(Curve.SECP256K1, path("m/44'/283'/0'/0/0")) }
    }

    @Test
    fun aPathOutsideTheAlgorandScopeIsRefusedBeforeAnyKeyIsDerivedAndIsLogged() {
        val logged = mutableListOf<KeyScopeViolation>()
        val scoped = ScopedKeyAccess(algorandManifest.keys, WalletKeyAccess(seed)) { logged += it }

        assertFailsWith<KeyScopeViolation> { scoped.signEd25519(Curve.ED25519_BIP32, path("m/44'/0'/0'/0/0"), ByteArray(0)) }

        assertEquals(1, logged.size)
    }

    @Test
    fun theCallsOfOtherCurvesAreNotHeldHere() {
        assertFailsWith<UnsupportedOperationException> {
            WalletKeyAccess(seed).signEcdsaSecp256k1(WalletKeyAccess.algorandPath(0), ByteArray(32))
        }
    }

    private fun path(text: String): Path = Path(
        text.removePrefix("m/").split('/').map { level ->
            PathStep(index = level.removeSuffix("'").toInt(), hardened = level.endsWith("'"))
        },
    )

    private companion object {
        /** The 24-word phrase of Mnemonic A, which the Ledger Algorand app derives its account keys from. */
        val MNEMONIC_A: List<String> = List(23) { "abandon" } + "art"

        /** The public key of Mnemonic A account 0, as LedgerEd25519Test asserts it. */
        const val ACCOUNT_0_PUBLIC_KEY = "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538"
    }
}
