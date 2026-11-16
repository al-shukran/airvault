package app.lensly.app

import app.lensly.app.sign.WalletView

/** The accounts of [wallets], each keyed by [addressKeyOf] its address. A Single Key wallet has one account, at index 0. */
fun internalAccountsOf(wallets: List<WalletView>): Map<String, InternalAccount> = buildMap {
    wallets.forEach { wallet ->
        for (index in 0 until wallet.accountCount) {
            put(
                addressKeyOf(wallet.addressAt(index)),
                InternalAccount(
                    displayName = wallet.accountNames[index],
                    autoName = autoAccountName(wallet.autoNameBase, index),
                    label = wallet.accountLabels[index],
                ),
            )
        }
    }
}
