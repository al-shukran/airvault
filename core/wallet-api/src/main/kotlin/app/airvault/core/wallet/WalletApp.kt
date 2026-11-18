package app.airvault.core.wallet

import app.airvault.core.model.ChainApp

/**
 * The Lumio (wallet) side of a chain app. Its members (providers, assets, transaction builder, fees, broadcaster and
 * pairing import) are added by the changes that own their types (spec 3.3). Until then it carries the chain-app
 * contract only.
 */
interface WalletApp : ChainApp
