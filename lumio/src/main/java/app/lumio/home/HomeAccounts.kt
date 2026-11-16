package app.lumio.home

import app.lensly.core.btc.ScriptType
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.networkId
import app.lumio.data.net.Family
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.ui.network.label

/** Where a row of a Home pick leads. Each variant names one account, or the Bitcoin send, which picks its own account in its form. */
sealed interface PickTarget {
    /** The Ethereum account of the wallet [walletId]. */
    data class Evm(val walletId: String) : PickTarget

    /** One Bitcoin account, by its key. */
    data class BitcoinAccount(val key: String) : PickTarget

    /** The Bitcoin accounts of the wallet [walletId]. */
    data class BitcoinWallet(val walletId: String) : PickTarget

    /** The Bitcoin send, which picks the account it sends from in its form. */
    data object Bitcoin : PickTarget
}

/** One row of a Home pick: where it leads, its [title] and its [detail]. */
data class AccountOption(val target: PickTarget, val title: String, val detail: String)

/** The rows of the Send and Receive pickers, and of an asset page's accounts, built from the paired wallets and their Bitcoin accounts. */
object HomeAccounts {
    /**
     * The sources a Send can start from on the side [mode]: each wallet's Ethereum account, and the Bitcoin send when the side holds a Bitcoin account.
     */
    fun sendFrom(mode: NetworkMode, wallets: List<PairedWallet>, bitcoin: List<BtcAccountRef>): List<AccountOption> = buildList {
        wallets.filter { it.evm != null }.forEach { wallet ->
            add(AccountOption(PickTarget.Evm(wallet.id), "Ethereum", wallet.name))
        }
        if (bitcoin.isNotEmpty()) {
            add(AccountOption(PickTarget.Bitcoin, "Bitcoin", "${bitcoin.size} ${plural(bitcoin.size)} on ${mode.label()}"))
        }
    }

    /**
     * The addresses a Receive can show on the side [mode]: each wallet's first Ethereum address, and each Bitcoin account of the side.
     */
    fun receiveFor(wallets: List<PairedWallet>, bitcoin: List<BtcAccountRef>): List<AccountOption> = buildList {
        wallets.filter { it.evm != null }.forEach { wallet ->
            add(AccountOption(PickTarget.Evm(wallet.id), "Ethereum", "${wallet.name}, address 1"))
        }
        bitcoin.forEach { ref ->
            val wallet = wallets.firstOrNull { it.id == ref.walletId }?.name.orEmpty()
            add(AccountOption(PickTarget.BitcoinAccount(ref.key), "Bitcoin ${scriptLabel(ref.account.scriptType)}", wallet))
        }
    }

    /**
     * The accounts that the page of [asset] lists: each wallet that holds the coin. An Ethereum coin is held by every wallet with an Ethereum account,
     * and a Bitcoin coin by the wallets that have an account on its network.
     */
    fun holding(asset: HomeAsset, wallets: List<PairedWallet>): List<AccountOption> = wallets.mapNotNull { wallet ->
        when (asset.network.family) {
            Family.EVM -> if (wallet.evm != null) AccountOption(PickTarget.Evm(wallet.id), "Ethereum", wallet.name) else null
            Family.BTC -> if (wallet.bitcoin.any { it.network.networkId() == asset.network }) {
                AccountOption(PickTarget.BitcoinWallet(wallet.id), "Bitcoin", wallet.name)
            } else {
                null
            }
            else -> null
        }
    }

    /**
     * The sources a Send of [asset] can start from. An EVM coin starts from the wallets with an Ethereum account, and a Bitcoin coin from the Bitcoin send.
     * A token has none, because Send sends only the network's own coin.
     */
    fun sendFor(asset: HomeAsset, mode: NetworkMode, wallets: List<PairedWallet>, bitcoin: List<BtcAccountRef>): List<AccountOption> {
        if (asset is TokenAsset) return emptyList()
        return sendFrom(mode, wallets, bitcoin.filter { it.account.network.networkId() == asset.network }).filter { option ->
            if (asset.network.family == Family.EVM) option.target is PickTarget.Evm else option.target == PickTarget.Bitcoin
        }
    }

    /** The addresses a Receive of [asset] can show: the wallets' Ethereum addresses for an EVM coin, and the Bitcoin accounts on its network for a Bitcoin coin. */
    fun receiveFor(asset: HomeAsset, wallets: List<PairedWallet>, bitcoin: List<BtcAccountRef>): List<AccountOption> =
        receiveFor(wallets, bitcoin.filter { it.account.network.networkId() == asset.network }).filter { option ->
            if (asset.network.family == Family.EVM) option.target is PickTarget.Evm else option.target is PickTarget.BitcoinAccount
        }

    /** The name of a Bitcoin script type as the pickers show it, such as "native segwit". */
    fun scriptLabel(type: ScriptType): String = when (type) {
        ScriptType.LEGACY -> "legacy"
        ScriptType.NESTED_SEGWIT -> "nested segwit"
        ScriptType.NATIVE_SEGWIT -> "native segwit"
        ScriptType.TAPROOT -> "taproot"
    }

    private fun plural(count: Int): String = if (count == 1) "account" else "accounts"
}
