package app.airvault.core.signer

import app.airvault.core.model.Approval
import app.airvault.core.model.ChainApp
import app.lensly.core.parse.WireException

/**
 * The Lensly (signer) side of a chain app. Its member for review is added by the change that owns its types (spec 3.2). Until then it
 * carries the chain-app contract, the request parser and the signing call.
 */
interface SignerApp : ChainApp {
    /** Parses this app's requests. It runs in the Parser Sandbox, with no key access (spec 3.2). */
    val parser: RequestParser

    /**
     * Why this app refuses [request] before any wallet is looked for, or null when it does not. The sign flow then routes nothing for the request
     * and shows the reason, which is recorded under the flow's wallet when it has one. It throws [WireException] for a body it cannot read.
     */
    fun refusalBeforeRouting(request: AppRequest): String? = null

    /**
     * True when [request] is for the Single Key wallet whose one account is at [address], as the wallet shows it. A Single Key has no master
     * fingerprint, so its requests name none in their header. An app whose requests can name a Single Key says so here. The default says no,
     * which is right for an app whose requests all name a master fingerprint.
     */
    fun isForSingleKey(request: AppRequest, address: String): Boolean = false

    /**
     * Signs a request the owner approved (spec 3.2). [keys] is scoped to this app's key scopes by the core, so a path outside them throws
     * [KeyScopeViolation]. The app checks that the key it signs with is the one the request names. An app that has not moved to the one
     * sign flow refuses, since its own step signs it.
     */
    fun sign(request: AppRequest, approval: Approval, keys: KeyAccess): SignResponse =
        throw UnsupportedOperationException("This app does not sign through the one sign flow yet.")
}

/** The answer to a signed request: the response UR type and its CBOR payload. The core frames the payload as a UR (spec 3.2). */
class SignResponse(val urType: String, val cbor: ByteArray)
