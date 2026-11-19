package app.airvault.core.model

/**
 * The checks a review asks the owner to tick: each warning's [Warning.acknowledge] label, once each, in the order the warnings name them
 * (spec 3.2). A warning with no label asks for no check.
 */
fun Review.checkLabels(): List<String> = warnings.mapNotNull { it.acknowledge }.distinct()

/**
 * The owner's approval of a [review] (spec 3.2, 8.3). It exists only when the review can be signed: no warning [stopsSigning], which is a BLOCK
 * or a DANGER with no check to tick, and every check the review names is ticked. A DANGER warning must be acknowledged by name, and a CAUTION
 * that names a check is held to the same rule, as the review screen has always held it. A sign call takes an approval, so nothing is signed
 * until this gate has passed.
 */
class Approval private constructor(val review: Review, val acknowledged: List<String>) {
    companion object {
        /** The approval of [review] by an owner who ticked the checks [ticked], or null while the review cannot be signed. */
        fun of(review: Review, ticked: Set<String>): Approval? {
            if (review.warnings.any { it.stopsSigning }) return null
            val checks = review.checkLabels()
            if (!ticked.containsAll(checks)) return null
            return Approval(review, checks)
        }
    }
}
