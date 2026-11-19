package app.lensly.core.wallet

import app.airvault.core.model.Curve
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.lensly.core.algorand.LedgerEd25519
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.PathStep

/**
 * The key access of one Mnemonic wallet (spec 3.2). Each call derives the key it needs from [seed] and zeroes it when the call returns. The
 * caller owns [seed] and zeroes it. Only the Algorand account key and the Solana account key are held: the Ledger key at
 * `m/44'/283'/account'/0/0` on ED25519_BIP32, which [LedgerEd25519] derives, and the SLIP-0010 key at `m/44'/501'/account'` on
 * ED25519_SLIP10. Other curves and paths are refused until their chains move to the one sign flow.
 */
class WalletKeyAccess(private val seed: ByteArray) : KeyAccess {
    override fun publicKey(curve: Curve, path: Path): PublicKey = when (curve) {
        Curve.ED25519_SLIP10 -> PublicKey(curve, SolanaAccount.publicKey(seed, solanaAccountOf(path)))
        else -> withAccount(curve, path) { key -> PublicKey(curve, key.publicKey.copyOf()) }
    }

    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature = unsupported()

    override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray = unsupported()

    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray = when (curve) {
        Curve.ED25519_SLIP10 -> signSolana(path, message)
        else -> withAccount(curve, path) { key -> Ed25519Signer.sign(key.kL, message) }
    }

    override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T = unsupported()

    private fun <T> withAccount(curve: Curve, path: Path, use: (LedgerEd25519.Key) -> T): T {
        val key = LedgerEd25519.algorandAccount(seed, algorandAccountOf(curve, path))
        try {
            return use(key)
        } finally {
            key.wipe()
        }
    }

    /** Signs [message] with the SLIP-0010 key of the Solana account at [path], and zeroes the key before it returns. */
    private fun signSolana(path: Path, message: ByteArray): ByteArray =
        SolanaAccount.node(seed, solanaAccountOf(path)).use { node ->
            val key = node.privateKey()
            try {
                Ed25519Signer.sign(key, message)
            } finally {
                key.fill(0)
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

/** The account index of a Solana path on ED25519_SLIP10, m/44'/501'/account'. Any other path is refused. */
private fun solanaAccountOf(path: Path): Int {
    val steps = path.steps
    val isSolana = steps.size == 3 &&
        steps[0] == PathStep(44, hardened = true) &&
        steps[1] == PathStep(501, hardened = true) &&
        steps[2].hardened
    require(isSolana) { "$path is not a Solana account path." }
    return steps[2].index
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
