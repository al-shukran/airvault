package app.lensly.core.wallet

import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.Curve
import app.airvault.core.signer.KeyScopeViolation
import app.airvault.core.signer.Path
import app.airvault.core.signer.ScopedKeyAccess
import app.lensly.core.algorand.LedgerEd25519
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.PathStep
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.byteVector64
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
    fun theAlgorandPathIsNotABitcoinKeyAndTheRawPrivateKeyIsNeverHanded() {
        val keys = WalletKeyAccess(seed)

        assertFailsWith<IllegalArgumentException> { keys.signEcdsaSecp256k1(WalletKeyAccess.algorandPath(0), ByteArray(32)) }
        assertFailsWith<UnsupportedOperationException> {
            keys.withPrivateKey(Curve.SECP256K1, btcPath(ScriptType.NATIVE_SEGWIT, 0, 0, 0)) { it }
        }
    }

    @Test
    fun theBitcoinPublicKeysAreTheKeysOfTheBtcAccountsOfTheSameSeed() {
        val keys = WalletKeyAccess(seed)
        for (type in ScriptType.entries) {
            for (accountIndex in listOf(0, 1)) {
                val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, type, accountIndex)
                for ((branch, index) in listOf(0 to 0, 0 to 5, 1 to 0, 1 to 19)) {
                    val path = btcPath(type, accountIndex, branch, index)
                    assertContentEquals(
                        account.publicKey(branch, index).value.toByteArray(),
                        keys.publicKey(Curve.SECP256K1, path).bytes,
                        "$type $path",
                    )
                }
            }
        }
    }

    @Test
    fun theEcdsaSignatureIsTheSignatureBitcoinKmpGivesForTheSameKeyWithItsRecoveryId() {
        val digest = Crypto.sha256("airvault".toByteArray())
        val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        val expected = Crypto.sign(digest, account.privateKey(0, 3)).toByteArray()

        val signed = WalletKeyAccess(seed).signEcdsaSecp256k1(btcPath(ScriptType.NATIVE_SEGWIT, 0, 0, 3), digest)

        assertContentEquals(expected.copyOfRange(0, 32), signed.r)
        assertContentEquals(expected.copyOfRange(32, 64), signed.s)
        val recovered = Crypto.recoverPublicKey(expected.byteVector64(), digest, signed.recoveryId)
        assertContentEquals(account.publicKey(0, 3).value.toByteArray(), recovered.value.toByteArray())
    }

    @Test
    fun theSchnorrSignatureIsTheTaprootSignatureBitcoinKmpGivesForTheSameKey() {
        val digest = Crypto.sha256("airvault".toByteArray())
        val merkleRoot = Crypto.sha256("script tree".toByteArray())
        val key = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT).privateKey(0, 3)
        val path = btcPath(ScriptType.TAPROOT, 0, 0, 3)
        val keys = WalletKeyAccess(seed)

        val keyPathOnly = Crypto.signSchnorr(digest.byteVector32(), key, Crypto.TaprootTweak.KeyPathTweak, null).toByteArray()
        val withScriptTree = Crypto.signSchnorr(
            digest.byteVector32(),
            key,
            Crypto.TaprootTweak.ScriptPathTweak(merkleRoot.byteVector32()),
            null,
        ).toByteArray()

        assertContentEquals(keyPathOnly, keys.signSchnorrBip340(path, digest, null))
        assertContentEquals(withScriptTree, keys.signSchnorrBip340(path, digest, merkleRoot))
        assertNotEquals(keyPathOnly.toList(), withScriptTree.toList())
    }

    @Test
    fun aPathThatIsNotABitcoinAccountKeyIsRefusedByEveryCall() {
        val keys = WalletKeyAccess(seed)
        val digest = ByteArray(32)
        for (text in listOf(
            "m/84'/1'/0'/2/0",
            "m/84'/1'/0'/0'/0",
            "m/83'/1'/0'/0/0",
            "m/84'/2'/0'/0/0",
            "m/84'/1'/0'/0",
            "m/84/1'/0'/0/0",
            "m/84'/1'/0'/0/0/0",
            "m/44'/283'/0'/0/0",
        )) {
            val path = path(text)
            assertFailsWith<IllegalArgumentException>(text) { keys.publicKey(Curve.SECP256K1, path) }
            assertFailsWith<IllegalArgumentException>(text) { keys.signEcdsaSecp256k1(path, digest) }
            assertFailsWith<IllegalArgumentException>(text) { keys.signSchnorrBip340(path, digest, null) }
        }
    }

    @Test
    fun aDigestIsThirtyTwoBytesAndAMerkleRootIsThirtyTwoBytes() {
        val keys = WalletKeyAccess(seed)
        val path = btcPath(ScriptType.NATIVE_SEGWIT, 0, 0, 0)

        assertFailsWith<IllegalArgumentException> { keys.signEcdsaSecp256k1(path, ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { keys.signSchnorrBip340(path, ByteArray(32), ByteArray(31)) }
    }

    private fun btcPath(type: ScriptType, account: Int, branch: Int, index: Int): Path =
        path("m/${type.purpose}'/1'/$account'/$branch/$index")

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
