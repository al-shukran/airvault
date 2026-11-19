package app.airvault.chains.algorand.wallet

import app.airvault.chains.algorand.AlgorandAddressCodec
import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.ChainManifest
import app.airvault.core.wallet.WalletApp

/** The Lumio side of the Algorand chain app. Its address rules are the ones every Algorand side shares. */
object AlgorandWallet : WalletApp {
    override val manifest: ChainManifest = algorandManifest

    override val addresses: AddressCodec = AlgorandAddressCodec
}
