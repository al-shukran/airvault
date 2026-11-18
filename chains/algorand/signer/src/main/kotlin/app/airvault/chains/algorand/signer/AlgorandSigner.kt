package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.SignerApp

/**
 * The Lensly side of the Algorand chain app. Only the manifest is wired so far. The address codec throws until it is
 * written, so nothing can use it by accident.
 */
object AlgorandSigner : SignerApp {
    override val manifest: ChainManifest = algorandManifest

    override val addresses: AddressCodec
        get() = TODO("The Algorand address codec is not written yet")
}
