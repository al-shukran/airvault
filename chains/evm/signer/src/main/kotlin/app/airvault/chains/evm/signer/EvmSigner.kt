package app.airvault.chains.evm.signer

import app.airvault.chains.evm.EvmAddressCodec
import app.airvault.chains.evm.evmManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/** The Lensly (signer) side of the EVM chain app. The manifest, the request parser and the address codec are wired. */
object EvmSigner : SignerApp {
    override val manifest: ChainManifest = evmManifest

    override val parser: RequestParser = EvmRequestParser

    override val addresses: AddressCodec = EvmAddressCodec
}
