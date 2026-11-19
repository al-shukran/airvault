package app.lumio.chains

import app.airvault.chains.algorand.wallet.AlgorandWallet
import app.airvault.chains.bitcoin.wallet.BitcoinWallet
import app.airvault.core.wallet.WalletApp

/**
 * The chain apps Lumio is built with (spec 2.4). Adding a chain adds its wallet here and nowhere else. Nothing reads
 * this list yet: the registry that validates it is built when the first chain uses it.
 */
val installedWallets: List<WalletApp> = listOf(AlgorandWallet, BitcoinWallet)
