package app.airvault.chains.solana.signer

import app.airvault.chains.solana.SolanaAddressCodec
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/** The Lensly side of the Solana chain app. The manifest, the request parser and the address codec are wired. */
object SolanaSigner : SignerApp {
    override val manifest: ChainManifest = solanaManifest

    override val parser: RequestParser = SolanaRequestParser

    override val addresses: AddressCodec = SolanaAddressCodec
}
