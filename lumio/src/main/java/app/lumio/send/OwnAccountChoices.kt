package app.lumio.send

import app.airvault.chains.algorand.wallet.AlgorandWallet
import app.airvault.chains.solana.wallet.SolanaWallet
import app.lensly.core.btc.BtcNetwork
import app.lumio.data.net.Networks
import app.lumio.pair.PairedWallet
import app.lumio.ui.send.SendChoice

/*
 * The owner's own accounts as recipient choices, under "Your Accounts": every account of the wallet on the chain except the one the send comes from.
 * Each is titled "Account N" by its place in the wallet, the name the send page gives an account that has none.
 */

/** The Bitcoin accounts of [wallet] on [network] whose first receive address is not [from]. */
fun bitcoinOwnChoices(wallet: PairedWallet, network: BtcNetwork, from: String): List<SendChoice> =
    wallet.bitcoin.mapIndexedNotNull { index, account ->
        val address = account.receiveAddress(0)
        if (account.network == network && address != from) SendChoice(title = "Account ${index + 1}", detail = "Your account", address = address) else null
    }

/** The Solana accounts of [wallet] whose address is not [from]. */
fun solanaOwnChoices(wallet: PairedWallet, from: String): List<SendChoice> =
    wallet.solana.mapIndexedNotNull { index, key ->
        val address = SolanaWallet.addresses.fromPublicKey(Networks.SOL_MAINNET, key)
        if (address != from) SendChoice(title = "Account ${index + 1}", detail = "Your account", address = address) else null
    }

/** The Algorand accounts of [wallet] whose address is not [from]. */
fun algorandOwnChoices(wallet: PairedWallet, from: String): List<SendChoice> =
    wallet.algorand.mapIndexedNotNull { index, key ->
        val address = AlgorandWallet.addresses.fromPublicKey(Networks.ALGO_MAINNET, key)
        if (address != from) SendChoice(title = "Account ${index + 1}", detail = "Your account", address = address) else null
    }
