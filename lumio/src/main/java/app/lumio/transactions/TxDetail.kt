package app.lumio.transactions

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.registry.Amounts
import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.btc.BtcPortfolio
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.SpamReason
import app.lumio.data.tx.TxSpam
import app.lumio.data.tx.TxStore
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSend
import app.lumio.tokens.CustomTokenStore
import java.math.BigDecimal

/**
 * Everything the detail screen shows about one transaction: the transaction itself, its value in dollars, its comment, its chain, its fee as text, its
 * explorer link when one is on file, and the reasons that the spam heuristic gives for hiding it, as words.
 */
data class TxDetail(
    val tx: LumioTx,
    val usd: Double?,
    val comment: String?,
    val chain: String,
    val feeText: String?,
    val explorerUrl: String?,
    val flags: List<String>,
)

/** The detail of the transaction [txId] of [account] in [vault], or null when the store does not hold it. */
internal suspend fun txDetailOf(app: LumioApp, vault: LumioVault, account: String, txId: String): TxDetail? {
    val tx = vault.txs.tx(account, txId) ?: return null
    val chains = vaultChains(app, vault)
    val chain = chains[tx.network]
    val asset = assetIdOf(tx)
    val price = app.prices.assetPrices(listOf(asset))[asset]
    val owned = CustomTokenStore.read(vault.session)
    val spam = TxSpam(
        isKnownToken = { network, contract -> app.isKnownToken(network, contract) },
        isCustomToken = { network, contract -> CustomTokenStore.holds(owned, network, contract) },
    )
    val reasons = spam.reasons(tx, vault.txs.counterparties(account))
    return TxDetail(
        tx = tx,
        usd = price?.let { BigDecimal(tx.amount).movePointLeft(tx.asset.decimals).toDouble() * it.usd },
        comment = vault.txs.comment(txId)?.text,
        chain = chainLabel(tx.network, chains),
        feeText = feeText(tx, chain),
        explorerUrl = explorerUrl(tx, chain),
        flags = reasons.map { flagLabel(it) }.sorted(),
    )
}

/** The fee of [tx] with its symbol, such as "0.00021 ETH", or null when the account paid no fee. */
internal fun feeText(tx: LumioTx, chain: EvmChain?): String? {
    val fee = tx.fee ?: return null
    val symbol = if (tx.network.isBtc) "BTC" else chain?.symbol.orEmpty()
    return "${Amounts.format(fee, TxStore.feeDecimals(tx.network))} $symbol".trim()
}

/** The explorer page of [tx], or null when no explorer is on file for its chain. */
internal fun explorerUrl(tx: LumioTx, chain: EvmChain?): String? =
    if (tx.network.isBtc) {
        BtcPortfolio.explorerTxUrl(if (tx.network.testnet) BtcNetwork.TESTNET4 else BtcNetwork.MAINNET, tx.hash)
    } else {
        chain?.let { EvmSend.explorerLink(it, tx.hash) }
    }

/** The reason of a spam flag, as the detail screen says it. */
internal fun flagLabel(reason: SpamReason): String = when (reason) {
    SpamReason.ZERO_VALUE -> "Moves no value"
    SpamReason.DUST -> "A dust amount of a token that Lumio does not know"
    SpamReason.LOOKALIKE -> "Sent from an address that looks like one you use"
    SpamReason.NAME -> "Its name or symbol points to a link or a call to action"
}
