package app.airvault.chains.bitcoin.signer

import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/**
 * The Lensly side of the Bitcoin chain app. The manifest and the request parser are wired. The address codec throws until it is
 * written, so nothing can use it by accident.
 */
object BitcoinSigner : SignerApp {
    override val manifest: ChainManifest = bitcoinManifest

    override val parser: RequestParser = BitcoinRequestParser

    override val addresses: AddressCodec
        get() = TODO("The Bitcoin address codec is not written yet")
}
