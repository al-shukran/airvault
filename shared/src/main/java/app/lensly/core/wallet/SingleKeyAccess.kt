package app.lensly.core.wallet

import app.airvault.core.model.Curve
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.byteVector32

/**
 * The key access of one Single Key wallet (spec 3.2). A Single Key holds one imported secp256k1 private key and no seed, so it answers only for
 * [heldPath], the path of the one account it has. The signer names that path: `EvmSigner.singleKeyPath` for an EVM wallet. Any other path is
 * refused, as [WalletKeyAccess] refuses a path it does not hold. The caller owns [privateKey] and zeroes it.
 */
class SingleKeyAccess(private val privateKey: ByteArray, private val heldPath: Path) : KeyAccess {
    init {
        require(privateKey.size == PRIVATE_KEY_BYTES) { "A private key is 32 bytes." }
    }

    override fun publicKey(curve: Curve, path: Path): PublicKey {
        requireHeld(curve, path)
        return PublicKey(curve, PrivateKey(privateKey.byteVector32()).publicKey().value.toByteArray())
    }

    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature {
        requireHeld(Curve.SECP256K1, path)
        return ecdsaOf(PrivateKey(privateKey.byteVector32()), digest32)
    }

    override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray =
        throw UnsupportedOperationException("A Single Key holds no BIP340 key.")

    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray =
        throw UnsupportedOperationException("A Single Key holds no Ed25519 key.")

    override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T =
        throw UnsupportedOperationException("This wallet's key access does not hand out raw private keys.")

    /** Refuses a key other than the one held: it must be a secp256k1 key at the held path. */
    private fun requireHeld(curve: Curve, path: Path) {
        require(curve == Curve.SECP256K1 && path == heldPath) { "$path on $curve is not the account this Single Key holds." }
    }

    private companion object {
        const val PRIVATE_KEY_BYTES = 32
    }
}
