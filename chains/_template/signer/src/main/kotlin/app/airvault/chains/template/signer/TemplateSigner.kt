package app.airvault.chains.template.signer

import app.airvault.chains.template.TemplateAddressCodec
import app.airvault.chains.template.templateManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignResponse
import app.airvault.core.signer.SignerApp

/** The Lensly side of the template chain app: its manifest, parser, addresses and signing. Each member is a TODO until the copy fills it. */
object TemplateSigner : SignerApp {
    override val manifest: ChainManifest get() = templateManifest

    override val parser: RequestParser = TemplateRequestParser

    override val addresses: AddressCodec = TemplateAddressCodec

    /** Signs an approved request with the key its header names, and checks that the key is the one the request asks for (spec 3.2). */
    override fun sign(request: AppRequest, approval: Approval, keys: KeyAccess): SignResponse =
        TODO("template: sign an approved request (spec 3.2)")
}
