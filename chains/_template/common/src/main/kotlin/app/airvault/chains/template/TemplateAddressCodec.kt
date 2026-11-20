package app.airvault.chains.template

import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainId

/** The chain's address rules: parse an address and derive one from a public key (spec 3.1). */
object TemplateAddressCodec : AddressCodec {
    override fun parse(chain: ChainId, text: String): AddressResult = TODO("template: parse and check addresses (spec 3.1)")

    override fun fromPublicKey(chain: ChainId, publicKey: ByteArray, kind: AddressKind): String =
        TODO("template: derive an address from a public key (spec 3.1)")
}
