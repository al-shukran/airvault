package app.airvault.chains.solana.signer

import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/**
 * The Lensly side of the Solana chain app. The manifest and the request parser are wired. The address codec throws until it is
 * written, so nothing can use it by accident.
 */
object SolanaSigner : SignerApp {
    override val manifest: ChainManifest = solanaManifest

    override val parser: RequestParser = SolanaRequestParser

    override val addresses: AddressCodec
        get() = TODO("The Solana address codec is not written yet")
}
