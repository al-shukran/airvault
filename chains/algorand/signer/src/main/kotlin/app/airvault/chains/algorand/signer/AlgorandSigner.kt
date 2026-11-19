package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/**
 * The Lensly side of the Algorand chain app. The manifest and the request parser are wired. The address codec throws until it is
 * written, so nothing can use it by accident.
 */
object AlgorandSigner : SignerApp {
    override val manifest: ChainManifest = algorandManifest

    override val parser: RequestParser = AlgorandRequestParser

    override val addresses: AddressCodec
        get() = TODO("The Algorand address codec is not written yet")
}
