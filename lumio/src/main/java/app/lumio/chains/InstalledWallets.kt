package app.lumio.chains

import app.airvault.chains.algorand.wallet.AlgorandWallet
import app.airvault.chains.bitcoin.wallet.BitcoinWallet
import app.airvault.chains.evm.wallet.EvmWallet
import app.airvault.chains.solana.wallet.SolanaWallet
import app.airvault.core.model.ChainRegistry
import app.airvault.core.wallet.WalletApp

/** The chain apps Lumio is built with (spec 2.4). Adding a chain adds its wallet here and nowhere else. */
val installedWallets: List<WalletApp> = listOf(AlgorandWallet, BitcoinWallet, EvmWallet, SolanaWallet)

/** The registry of [installedWallets]: the pairing import asks it which app serves the chain of an exported account. */
val walletRegistry: ChainRegistry<WalletApp> = ChainRegistry(installedWallets)
