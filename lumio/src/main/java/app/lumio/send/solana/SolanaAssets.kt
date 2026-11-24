package app.lumio.send.solana

import app.lensly.core.solana.SolanaTokenAccounts
import app.airvault.chains.solana.signer.SolanaTokenList
import app.lumio.chains.ChainHoldings
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.SplBalance
import java.math.BigDecimal
import java.math.BigInteger

/** A coin a Solana send can move: SOL ([token] null), or an SPL token of the classic program, with the [symbol] and [decimals] the picker shows. */
class SolanaSendAsset(val token: SplBalance?, val symbol: String, val decimals: Int) {
    /** The coin's key in the picker: its token's mint, or [SolanaAssets.SOL_KEY] for SOL. */
    val key: String get() = token?.mint ?: SolanaAssets.SOL_KEY
}

/** The coins a Solana send offers, the plan of a send of each coin, and the amounts in base units. */
object SolanaAssets {
    /** The picker's key for SOL. */
    const val SOL_KEY = "sol"

    /** The rent of a token account, 165 bytes: a send to a recipient who has none opens one, and pays this rent once. */
    const val TOKEN_ACCOUNT_BYTES = 165L

    /** SOL's decimal places: a lamport is a billionth of a SOL. */
    private const val SOL_DECIMALS = 9

    /** SOL, which the account always holds. */
    val native = SolanaSendAsset(null, "SOL", SOL_DECIMALS)

    /**
     * The coins the send offers: SOL, then each SPL token of the classic program that the account holds with an amount above zero. A Token-2022 token can
     * be shown but not sent yet, so it is not offered. A token takes its symbol from [names], or else its shortened mint.
     */
    fun choices(balances: List<SplBalance>, names: SolanaTokenList): List<SolanaSendAsset> = listOf(native) + balances
        .filter { it.program == SolanaTokenAccounts.TOKEN_PROGRAM && it.amount.signum() > 0 }
        .map { balance -> SolanaSendAsset(balance, names.token(balance.mint)?.symbol ?: ChainHoldings.shortened(balance.mint), balance.decimals) }

    /**
     * The plan of a send of [units] of [asset] from [account] to [recipient]: lamports of SOL, or base units of the token. [sol] is the account's SOL.
     * [recipientAccountExists] says whether the recipient has a token account for the mint, and [rentLamports] is the rent a new one would pay. Throws
     * IllegalArgumentException with the owner's words, as the planners do.
     */
    fun plan(
        network: NetworkId,
        asset: SolanaSendAsset,
        account: SolanaSendAccount,
        recipient: ByteArray,
        units: BigInteger,
        sol: BigInteger,
        nonceValue: ByteArray,
        requestId: ByteArray,
        recipientAccountExists: Boolean,
        rentLamports: Long,
        priority: Boolean = false,
    ): SolanaSendPlan {
        require(units.bitLength() < Long.SIZE_BITS) { "The amount is more than all of this coin there is." }
        val token = asset.token
            ?: return SolanaSend.plan(network, account, recipient, units.toLong(), sol, nonceValue, requestId, priority)
        require(units <= token.amount) { "More than your available ${text(token.amount, token.decimals)} ${asset.symbol}" }
        return SolanaSplSend.plan(
            network, account, recipient, token, units.toLong().toULong(), sol, nonceValue, requestId, recipientAccountExists, rentLamports, priority,
        )
    }

    /** The amount in [units] of a coin with [decimals] places, with no trailing zeros, as the form and the review show it. */
    fun text(units: BigInteger, decimals: Int): String = BigDecimal(units, decimals).stripTrailingZeros().toPlainString()

    /** [text] for a token's amount, which a plan holds as an unsigned 64-bit number. */
    fun text(units: ULong, decimals: Int): String = text(BigInteger(units.toString()), decimals)
}
