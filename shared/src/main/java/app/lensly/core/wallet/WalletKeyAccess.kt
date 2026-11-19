package app.lensly.core.wallet

import app.airvault.core.model.Curve
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.lensly.core.algorand.LedgerEd25519
import app.lensly.core.ur.PathStep

/**
 * The key access of one Mnemonic wallet (spec 3.2). Each call derives the key it needs from [seed] and zeroes it when the call returns. The
 * caller owns [seed] and zeroes it. Only the Algorand account key is held: the Ledger key at `m/44'/283'/account'/0/0` on ED25519_BIP32, which
 * [LedgerEd25519] derives. Other curves and paths are refused until their chains move to the one sign flow.
 */
class WalletKeyAccess(private val seed: ByteArray) : KeyAccess {
    override fun publicKey(curve: Curve, path: Path): PublicKey =
        withAccount(curve, path) { key -> PublicKey(curve, key.publicKey.copyOf()) }

    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature = unsupported()

    override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray = unsupported()

    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray =
        withAccount(curve, path) { key -> Ed25519Signer.sign(key.kL, message) }

    override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T = unsupported()

    private fun <T> withAccount(curve: Curve, path: Path, use: (LedgerEd25519.Key) -> T): T {
        val key = LedgerEd25519.algorandAccount(seed, algorandAccountOf(curve, path))
        try {
            return use(key)
        } finally {
            key.wipe()
        }
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("This wallet's key access holds the Algorand key only.")

    companion object {
        /** The derivation path of Algorand account [account]: `m/44'/283'/account'/0/0`, the path the Ledger Algorand app derives. */
        fun algorandPath(account: Int): Path = Path(
            listOf(
                PathStep(44, hardened = true),
                PathStep(ALGORAND_COIN_TYPE, hardened = true),
                PathStep(account, hardened = true),
                PathStep(0, hardened = false),
                PathStep(0, hardened = false),
            ),
        )

        private const val ALGORAND_COIN_TYPE = 283
    }
}

/** The account index of an Algorand path on ED25519_BIP32. Any other curve or path is refused. */
private fun algorandAccountOf(curve: Curve, path: Path): Int {
    require(curve == Curve.ED25519_BIP32) { "Only Ed25519 BIP32 keys are held here." }
    val steps = path.steps
    val isAlgorand = steps.size == 5 &&
        steps[0] == PathStep(44, hardened = true) &&
        steps[1] == PathStep(283, hardened = true) &&
        steps[2].hardened &&
        steps[3] == PathStep(0, hardened = false) &&
        steps[4] == PathStep(0, hardened = false)
    require(isAlgorand) { "$path is not an Algorand account path." }
    return steps[2].index
}
