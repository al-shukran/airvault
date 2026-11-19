package app.airvault.core.signer

import app.airvault.core.model.Curve
import app.lensly.core.ur.PathStep

/**
 * A concrete derivation path with every level fixed, such as m/44'/283'/0'/0/0 (spec 3.2). Wildcards exist only in the
 * manifest's path prefixes.
 */
data class Path(val steps: List<PathStep>) {
    override fun toString(): String = steps.joinToString(separator = "/", prefix = "m/") {
        if (it.hardened) "${it.index}'" else "${it.index}"
    }
}

/** A public key on [curve], in the curve's own encoding. */
class PublicKey(val curve: Curve, val bytes: ByteArray)

/** An ECDSA signature over secp256k1, low-S, with its recovery id (spec 3.2). */
class EcdsaSignature(val r: ByteArray, val s: ByteArray, val recoveryId: Int)

/**
 * The key access a chain app gets (spec 3.2). Key material exists only inside these calls and is zeroed after. The app
 * never sees the seed or the mnemonic.
 */
interface KeyAccess {
    fun publicKey(curve: Curve, path: Path): PublicKey

    fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature

    fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray

    fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray

    /** Only with Capability.RAW_PRIVATE_KEY. The bytes are zeroed when [block] returns. */
    fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T
}

/**
 * A key request named a path that no key scope of its curve covers (spec 2.2 rule 6). The core logs it in the review
 * record.
 */
class KeyScopeViolation(val curve: Curve, val path: Path) :
    RuntimeException("$path is outside every key scope of $curve")
