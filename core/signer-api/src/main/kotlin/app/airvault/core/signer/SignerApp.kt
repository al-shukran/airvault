package app.airvault.core.signer

import app.airvault.core.model.ChainApp

/**
 * The Lensly (signer) side of a chain app. Its members for review and signing are added by the changes that own their types
 * (spec 3.2). Until then it carries the chain-app contract and the request parser.
 */
interface SignerApp : ChainApp {
    /** Parses this app's requests. It runs in the Parser Sandbox, with no key access (spec 3.2). */
    val parser: RequestParser
}
