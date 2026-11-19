package app.airvault.core.conformance

import app.airvault.core.model.ChainApp
import app.airvault.core.model.ChainRegistry
import app.airvault.core.model.Curve
import app.airvault.core.model.KeyScope
import app.airvault.core.model.PathPart
import app.airvault.core.model.PathTemplate
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.KeyScopeViolation
import app.airvault.core.signer.Path
import app.airvault.core.signer.allows
import app.lensly.core.ur.PathStep

/** One failed check of the kit (spec 9.1). [check] names the check and [message] says what is wrong. */
data class ConformanceFailure(val check: String, val message: String)

/**
 * The manifest checks (spec 9.1): app id, apiVersion, CAIP-2 ids, key scopes (2.2), field specs (6.1) and UR types. The
 * registry enforces these rules when it loads an app, so the kit reports the registry's refusal.
 */
fun checkManifest(app: ChainApp): List<ConformanceFailure> = try {
    ChainRegistry(listOf(app))
    emptyList()
} catch (e: IllegalArgumentException) {
    listOf(ConformanceFailure("manifest", e.message.orEmpty()))
}

/**
 * The key-scope checks (spec 2.2 rule 6, 9.1), run through the key access the app signs with. The path of each prefix
 * must derive. Every path in [outsideOf] must throw [KeyScopeViolation].
 */
fun checkKeyScopes(app: ChainApp, keys: KeyAccess): List<ConformanceFailure> {
    val scopes = app.manifest.keys
    val coins = app.manifest.networks.map { it.slip44 } + scopes.flatMap { it.pathPrefixes }.mapNotNull { coinType(it) }
    val unusedCoin = (coins.maxOrNull() ?: 0) + 1
    val failures = mutableListOf<ConformanceFailure>()
    for (scope in scopes) {
        for (prefix in scope.pathPrefixes) {
            val inside = prefix.instance()
            outcome(keys, scope.curve, inside)?.let { error ->
                failures += ConformanceFailure(
                    "key scope",
                    "${scope.curve} $inside is inside $prefix but was refused (${error.javaClass.simpleName})",
                )
            }
            for ((curve, path) in outsideOf(scopes, scope, prefix, unusedCoin)) {
                val error = outcome(keys, curve, path)
                if (error !is KeyScopeViolation) {
                    val what = if (error == null) "was derived" else "threw ${error.javaClass.simpleName}"
                    failures += ConformanceFailure(
                        "key scope",
                        "$curve $path is outside $prefix but $what, expected KeyScopeViolation",
                    )
                }
            }
        }
    }
    return failures
}

/**
 * Paths just outside [prefix] that no scope of their curve covers. Each level of the prefix is flipped in turn, the
 * coin type is one that no app declares, and the same path is tried on every other curve.
 */
private fun outsideOf(
    scopes: List<KeyScope>,
    scope: KeyScope,
    prefix: PathTemplate,
    unusedCoin: Int,
): List<Pair<Curve, Path>> {
    val inside = prefix.instance()
    val steps = inside.steps
    val flipped = steps.indices.map { level ->
        inside.withStep(level, steps[level].copy(hardened = !steps[level].hardened))
    }
    val otherCoin = if (steps.size > 1) listOf(inside.withStep(1, steps[1].copy(index = unusedCoin))) else emptyList()
    val candidates = flipped.map { scope.curve to it } +
        otherCoin.map { scope.curve to it } +
        (Curve.entries - scope.curve).map { it to inside }
    return candidates.filterNot { (curve, path) -> scopes.allows(curve, path) }
}

/** The result of one derivation: null when the key access returned, the thrown error otherwise. */
private fun outcome(keys: KeyAccess, curve: Curve, path: Path): Throwable? = try {
    keys.publicKey(curve, path)
    null
} catch (error: Throwable) {
    error
}

/** A path inside this prefix: each wildcard takes index 0 at its own hardening. */
private fun PathTemplate.instance(): Path =
    Path(parts.map { PathStep(index = (it as? PathPart.Fixed)?.index ?: 0, hardened = it.hardened) })

private fun Path.withStep(level: Int, step: PathStep): Path =
    Path(steps.mapIndexed { i, old -> if (i == level) step else old })

/** The SLIP-44 coin type at level 1 of a prefix, or null when the level is a wildcard. */
private fun coinType(prefix: PathTemplate): Int? = (prefix.parts.getOrNull(1) as? PathPart.Fixed)?.index
