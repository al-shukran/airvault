package app.airvault.core.signer

import app.airvault.core.model.ChainApp

/**
 * The Lensly (signer) side of a chain app. Its members for parsing, review and signing are added by the changes that
 * own their types (spec 3.2). Until then it carries the chain-app contract only.
 */
interface SignerApp : ChainApp
