package app.airvault.chains.bitcoin.signer

import app.airvault.chains.bitcoin.BitcoinAddressCodec
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/** The Lensly side of the Bitcoin chain app. The manifest, the request parser and the address codec are wired. */
object BitcoinSigner : SignerApp {
    override val manifest: ChainManifest = bitcoinManifest

    override val parser: RequestParser = BitcoinRequestParser

    override val addresses: AddressCodec = BitcoinAddressCodec
}
