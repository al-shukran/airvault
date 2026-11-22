package app.lumio.ui.asset

import app.airvault.core.model.AccountId
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** One account's share of a coin: its name, its wallet's name, index and address as Holdings Breakdown writes them, and the amount it holds. */
internal data class AccountHolding(
    val walletId: String,
    val accountId: AccountId,
    val chain: String,
    val name: String,
    val detail: String,
    val amount: BigDecimal,
)

/** The accounts of one chain that hold a coin, with their total. The accounts are the largest first. */
internal data class ChainHolding(val chain: String, val total: BigDecimal, val accounts: List<AccountHolding>)

/**
 * The native coin [symbol] as the accounts hold it, one group per chain. Accounts that hold nothing of [symbol] are left out. Chains are in name
 * order, as the design lists them, and each chain's accounts are the largest first.
 */
internal fun chainHoldings(accounts: List<WalletAccount>, symbol: String, walletName: String): List<ChainHolding> = accounts
    .mapNotNull { account ->
        val held = account.balance?.takeIf { it.symbol == symbol && it.balance.signum() > 0 } ?: return@mapNotNull null
        AccountHolding(
            walletId = account.walletId,
            accountId = account.id,
            chain = account.networkName,
            name = account.name ?: account.label ?: account.networkName,
            detail = "$walletName #${account.index} · ${shortAddress(account.address)}",
            amount = held.balance,
        )
    }
    .groupBy { it.chain }
    .map { (chain, rows) ->
        ChainHolding(chain = chain, total = rows.map { it.amount }.reduce(BigDecimal::add), accounts = rows.sortedByDescending { it.amount })
    }
    .sortedBy { it.chain }

/** An amount of a coin as the page writes it: the digits without trailing zeros, then the ticker. */
internal fun amountText(amount: BigDecimal, symbol: String): String = "${amount.stripTrailingZeros().toPlainString()} $symbol"

/** An address shortened to its first six and last four characters, joined by an ellipsis: `0x1020…122b`. */
internal fun shortAddress(address: String): String = if (address.length <= SHORT_ADDRESS_LENGTH) address else address.take(6) + "…" + address.takeLast(4)

/** Dollars to the cent, as the asset page writes them: `$4,285.70`. */
internal fun fiatText(usd: Double): String = FIAT.format(usd)

/** The count of accounts and chains as the asset page's holdings row reads them: `5 accounts · 3 chains`. */
internal fun holdingsSummary(accounts: Int, chains: Int): String = "${counted(accounts, "account")} · ${counted(chains, "chain")}"

private fun counted(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

private const val SHORT_ADDRESS_LENGTH = 12

private val FIAT: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)
