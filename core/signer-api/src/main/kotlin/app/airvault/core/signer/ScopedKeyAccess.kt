package app.airvault.core.signer

import app.airvault.core.model.Curve
import app.airvault.core.model.KeyScope
import app.airvault.core.model.PathPart
import app.airvault.core.model.PathTemplate

/** Whether this prefix covers [path]: the path is at least as deep, and every level of the prefix matches it. */
fun PathTemplate.covers(path: Path): Boolean =
    path.steps.size >= parts.size && parts.withIndex().all { (level, part) ->
        val step = path.steps[level]
        part.hardened == step.hardened && (part !is PathPart.Fixed || part.index == step.index)
    }

/** Whether some scope on [curve] covers [path] (spec 2.2). */
fun List<KeyScope>.allows(curve: Curve, path: Path): Boolean =
    any { scope -> scope.curve == curve && scope.pathPrefixes.any { it.covers(path) } }

/**
 * The core's run-time check (spec 2.2 rule 6). Each call names a curve and a path. A request that no scope allows
 * throws [KeyScopeViolation] before [delegate] sees it.
 */
class ScopedKeyAccess(private val scopes: List<KeyScope>, private val delegate: KeyAccess) : KeyAccess {
    override fun publicKey(curve: Curve, path: Path): PublicKey {
        requireInScope(curve, path)
        return delegate.publicKey(curve, path)
    }

    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature {
        requireInScope(Curve.SECP256K1, path)
        return delegate.signEcdsaSecp256k1(path, digest32)
    }

    override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray {
        requireInScope(Curve.SECP256K1, path)
        return delegate.signSchnorrBip340(path, digest32, tweak)
    }

    override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray {
        requireInScope(curve, path)
        return delegate.signEd25519(curve, path, message)
    }

    override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T {
        requireInScope(curve, path)
        return delegate.withPrivateKey(curve, path, block)
    }

    private fun requireInScope(curve: Curve, path: Path) {
        if (!scopes.allows(curve, path)) throw KeyScopeViolation(curve, path)
    }
}
