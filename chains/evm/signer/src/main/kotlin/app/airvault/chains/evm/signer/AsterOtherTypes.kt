package app.airvault.chains.evm.signer

import app.airvault.core.model.Danger
import app.airvault.core.model.Warning
import app.lensly.core.registry.Grant
import app.lensly.core.registry.OffChainDomain

private const val CODE_ASTER_TYPE = "evm.typed.aster-type"

private const val ACK_BUILDER = "I understand this lets another account take fees from this account."
private const val BUILDER_TEXT = "This lets another account take fees from this account. Lensly cannot show what the fees cover, so check who asked " +
    "for the signature before you sign."
private const val ACK_MIGRATION = "I understand this may move assets out of this account."
private const val MIGRATION_TEXT = "This may move assets out of this account. Lensly cannot show where they go, so check who asked for the " +
    "signature before you sign."
private const val ACK_UNKNOWN_ASTER = "I understand Lensly cannot tell what this AsterDex message does."
private const val UNKNOWN_ASTER_TEXT = "Lensly does not know this kind of AsterDex message, so it cannot tell what it does. Check who asked for the " +
    "signature before you sign."

/**
 * The warning of a typed message on a domain that [domain] names but whose primary type [primaryType] the product does not label, or null. The
 * field layouts of these types are not settled, so the review cannot explain them: those that grant control or move assets are a DANGER, and
 * any other is a CAUTION. An ApproveAgent is the DANGER of the agent approval that the review gives it on every domain, so it adds none here.
 */
internal fun otherTypeWarning(domain: OffChainDomain?, primaryType: String): Warning? {
    if (domain == null || !domain.warnsOtherTypes || primaryType in domain.primaryTypes || primaryType.substringAfterLast(':') == "ApproveAgent") return null
    return when (domain.grantTypes[primaryType]) {
        Grant.AGENT -> Warning(Danger.DANGER, CODE_ASTER_TYPE, APPROVE_AGENT_TEXT, acknowledge = ACK_APPROVE_AGENT)
        Grant.BUILDER -> Warning(Danger.DANGER, CODE_ASTER_TYPE, BUILDER_TEXT, acknowledge = ACK_BUILDER)
        Grant.ASSET_MIGRATION -> Warning(Danger.DANGER, CODE_ASTER_TYPE, MIGRATION_TEXT, acknowledge = ACK_MIGRATION)
        null -> Warning(Danger.CAUTION, CODE_ASTER_TYPE, UNKNOWN_ASTER_TEXT, acknowledge = ACK_UNKNOWN_ASTER)
    }
}
