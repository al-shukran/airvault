package app.airvault.chains.evm.signer

import app.airvault.chains.evm.evmManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerApp

/**
 * The Lensly (signer) side of the EVM chain app. The manifest and the request parser are wired. The address codec throws until it is
 * written, so nothing can use it by accident.
 */
object EvmSigner : SignerApp {
    override val manifest: ChainManifest = evmManifest

    override val parser: RequestParser = EvmRequestParser

    override val addresses: AddressCodec
        get() = TODO("The EVM address codec is not written yet")
}
