package app.airvault.chains.template.wallet

import app.airvault.chains.template.TemplateAddressCodec
import app.airvault.chains.template.templateManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.AccountExport
import app.airvault.core.wallet.WalletAccount
import app.airvault.core.wallet.WalletApp

/** The Lumio side of the template chain app: its manifest, its addresses, and the accounts a pairing gives it (spec 3.3.1). */
object TemplateWallet : WalletApp {
    override val manifest: ChainManifest get() = templateManifest

    override val addresses: AddressCodec = TemplateAddressCodec

    /** The accounts one exported key gives this chain, checking the key's curve and path against the manifest (spec 3.3.1, 7.5). */
    override fun accountFrom(export: AccountExport): List<WalletAccount> =
        TODO("template: map an exported key to its accounts (spec 3.3.1)")
}
