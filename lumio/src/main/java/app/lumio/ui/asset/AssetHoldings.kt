package app.lumio.ui.asset

import app.airvault.core.model.AccountId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.tokens.TokenHolding
import app.lumio.wallets.WalletAccount
import app.lumio.watchlist.WatchedAsset
import java.math.BigDecimal

/** One account's share of a coin: its name, its wallet's name and index, and its address as Holdings Breakdown writes them, and the amount it holds. */
internal data class AccountHolding(
    val walletId: String,
    val accountId: AccountId,
    val chain: String,
    val name: String,
    val detail: String,
    val address: String,
    val amount: BigDecimal,
)

/** The accounts of one chain that hold a coin, with their total and the network that names the chain's logo. The accounts are the largest first. */
internal data class ChainHolding(val chain: String, val network: NetworkId, val total: BigDecimal, val accounts: List<AccountHolding>)

/** What one account holds of a coin, and the network and chain that amount is on. */
internal data class WalletHolding(val account: WalletAccount, val network: NetworkId, val chain: String, val amount: BigDecimal)

/**
 * What [accounts] hold of [asset], whose native coin is named [symbol]. A native coin is read from each account's balance. A token is read from the
 * token balances in [tokens] that sit on the asset's chain, scaled by the token's decimals. Accounts that hold nothing of the coin are left out.
 */
internal fun holdingsOf(
    asset: WatchedAsset,
    symbol: String,
    accounts: List<WalletAccount>,
    tokens: Map<WalletAccount, List<TokenHolding>>,
): List<WalletHolding> = if (asset.contract == null) {
    accounts.mapNotNull { account ->
        account.balance?.takeIf { it.symbol == symbol && it.balance.signum() > 0 }?.let { WalletHolding(account, it.network, account.networkName, it.balance) }
    }
} else {
    accounts.flatMap { account ->
        tokens[account].orEmpty()
            .filter { it.amount.signum() > 0 && it.token.contract.equals(asset.contract, ignoreCase = true) && Networks.evm(it.chain.chainId) == asset.network.chain }
            .map { WalletHolding(account, asset.network, it.chain.name, BigDecimal(it.amount).movePointLeft(it.token.decimals)) }
    }
}

/**
 * Whether [account] can hold [asset]: a native coin on the account's own network, or a token on an account of the token's family: an ERC-20 on any
 * Ethereum account, an SPL token on a Solana account, an Algorand asset on an Algorand account.
 */
internal fun canHold(account: WalletAccount, asset: WatchedAsset): Boolean =
    if (asset.contract == null) account.coin?.network == asset.network else account.namespace == asset.network.chain.namespace

/** The name of [account] as the Asset page writes it: the owner's name, else its label, else its network. */
internal fun accountName(account: WalletAccount): String = account.name ?: account.label ?: account.networkName

/** The line under an account's name: its wallet's name and its index, as `Lumio #0`. Its address is a line of its own. */
internal fun accountDetail(walletName: String, account: WalletAccount): String = "$walletName #${account.index}"

/**
 * A coin's holdings as [holdingsOf] reads them, one group per chain. Chains are in name order, as the design lists them, and each chain's accounts are
 * the largest first.
 */
internal fun chainHoldings(holdings: List<WalletHolding>, walletName: String): List<ChainHolding> = holdings
    .groupBy { it.chain }
    .map { (chain, rows) ->
        val accounts = rows.map { holding ->
            AccountHolding(
                walletId = holding.account.walletId,
                accountId = holding.account.id,
                chain = chain,
                name = accountName(holding.account),
                detail = accountDetail(walletName, holding.account),
                address = holding.account.address,
                amount = holding.amount,
            )
        }
        ChainHolding(
            chain = chain,
            network = rows.first().network,
            total = rows.map { it.amount }.reduce(BigDecimal::add),
            accounts = accounts.sortedByDescending { it.amount },
        )
    }
    .sortedBy { it.chain }

/** An amount of a coin as the page writes it: the digits without trailing zeros, then the ticker. */
internal fun amountText(amount: BigDecimal, symbol: String): String = "${amount.stripTrailingZeros().toPlainString()} $symbol"

/** The count of accounts and chains as the asset page's holdings row reads them: `5 accounts · 3 chains`. */
internal fun holdingsSummary(accounts: Int, chains: Int): String = "${counted(accounts, "account")} · ${counted(chains, "chain")}"

private fun counted(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

