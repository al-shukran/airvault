package app.lumio.ui.receive

import app.lensly.app.addressKeyOf
import app.lensly.app.autoAccountName
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.pair.PairedWallet
import app.lumio.pair.ownAccountsOf
import app.lumio.receive.ReceiveAccount
import app.lumio.receive.ReceiveAnswers
import app.lumio.receive.ReceiveCoin
import app.lumio.receive.ReceiveNetwork
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.wallets.AccountCoin
import app.lumio.wallets.WalletAccount

/**
 * The answers that a Receive opened from the asset [row] of [account] starts with. The coin, its network and the account are all known, so the Receive
 * opens at its sheet with no question. The account is named as Lensly names it: its own name when the pairing sent one, else its Auto-Name, with its
 * label and group when the pairing sent them. Only Ethereum accounts carry names in a pairing, so the others keep their Auto-Name. An Algorand asset row is
 * keyed by its asset ID, which the Receive carries so that it can ask whether the account has opted in to the asset.
 */
internal fun receiveStartOf(row: AssetRowModel, account: WalletAccount, wallets: List<PairedWallet>): ReceiveAnswers {
    val receiveAccount = receiveAccountOf(account, wallets)
    val network = ReceiveNetwork(name = row.network, icon = row.icon, accounts = listOf(receiveAccount))
    val coin = ReceiveCoin(
        symbol = row.symbol,
        name = row.name,
        subtitle = "",
        held = !row.zero,
        icon = row.icon,
        networks = listOf(network),
        algorandAssetId = if (account.namespace == Networks.ALGORAND) row.assetKey.toULongOrNull() else null,
    )
    return ReceiveAnswers(coin = coin, network = network, account = receiveAccount)
}

/**
 * The answers that a Receive opened from the native coin of [account] starts with. The coin comes from the account, not from its balance, so the Receive
 * opens at its sheet while the balance is still loading. [icon] is the logo of [coin].
 */
internal fun receiveNativeStartOf(
    account: WalletAccount,
    coin: AccountCoin,
    icon: AssetIconSpec,
    wallets: List<PairedWallet>,
): ReceiveAnswers {
    val receiveAccount = receiveAccountOf(account, wallets)
    val network = ReceiveNetwork(name = account.networkName, icon = icon, accounts = listOf(receiveAccount))
    val receiveCoin = ReceiveCoin(
        symbol = coin.symbol,
        name = coin.name,
        subtitle = "",
        held = (account.balance?.balance?.signum() ?: 0) > 0,
        icon = icon,
        networks = listOf(network),
    )
    return ReceiveAnswers(coin = receiveCoin, network = network, account = receiveAccount)
}

/**
 * The answers that a Receive opened from the Asset page starts with: the coin named by [symbol] and [name], drawn by [icon], on the [network] of the
 * coin, into [account]. The coin is preselected, so the Receive opens at its sheet. [held] says whether the wallet holds the coin, and [networkIcon]
 * draws the network.
 */
internal fun receiveCoinStartOf(
    account: WalletAccount,
    network: NetworkId,
    symbol: String,
    name: String,
    icon: AssetIconSpec,
    networkIcon: AssetIconSpec,
    held: Boolean,
    wallets: List<PairedWallet>,
): ReceiveAnswers {
    val receiveAccount = receiveAccountOf(account, wallets)
    val receiveNetwork = ReceiveNetwork(name = network.name, icon = networkIcon, accounts = listOf(receiveAccount))
    val coin = ReceiveCoin(symbol = symbol, name = name, subtitle = "", held = held, icon = icon, networks = listOf(receiveNetwork))
    return ReceiveAnswers(coin = coin, network = receiveNetwork, account = receiveAccount)
}

/** The account as Lensly names it, with the label and group that its pairing sent. */
private fun receiveAccountOf(account: WalletAccount, wallets: List<PairedWallet>): ReceiveAccount {
    val wallet = wallets.firstOrNull { it.id == account.walletId }
    val key = addressKeyOf(account.address)
    val named = ownAccountsOf(wallets)[key]
    return ReceiveAccount(
        name = named?.displayName ?: named?.autoName ?: autoAccountName(wallet?.name.orEmpty(), account.index),
        label = named?.label,
        group = wallet?.evmAccounts?.firstOrNull { addressKeyOf(it.address) == key }?.group,
        address = account.address,
    )
}
