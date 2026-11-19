package app.airvault.core.wallet

import app.airvault.core.model.ChainApp
import app.airvault.core.signer.AccountExport

/**
 * The Lumio (wallet) side of a chain app. Its members for providers, assets, transaction builder, fees and broadcast are
 * added by the changes that own their types (spec 3.3). Until then it carries the chain-app contract and the pairing import.
 */
interface WalletApp : ChainApp {
    /** The accounts that one exported key gives this app, for the pairing import (spec 3.3.1, 7.5). */
    fun accountFrom(export: AccountExport): List<WalletAccount>
}
