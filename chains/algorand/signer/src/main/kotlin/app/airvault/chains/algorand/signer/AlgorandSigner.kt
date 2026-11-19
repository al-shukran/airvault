package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgorandAddressCodec
import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/** The Lensly side of the Algorand chain app: its manifest, its request parser and the address rules every Algorand side shares. */
object AlgorandSigner : SignerApp {
    override val manifest: ChainManifest = algorandManifest

    override val parser: RequestParser = AlgorandRequestParser

    override val addresses: AddressCodec = AlgorandAddressCodec
}
