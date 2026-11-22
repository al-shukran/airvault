package app.lumio.send.solana

import app.lensly.core.solana.SolanaPriorityFee
import app.lensly.core.solana.SolanaSplTransfer
import app.lensly.core.solana.SolanaTokenAccounts
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.SolanaAccountState
import fr.acinq.bitcoin.Base58
import java.math.BigInteger

/** The SPL token that a Solana send moves: its [mint] in base58, its [decimals], and its [amount] in base units. */
class SplSendToken(val mint: String, val decimals: Int, val amount: ULong)

/** The reason a send is refused for a token of the Token-2022 program. Its transfers can carry hooks and fees that Lumio does not read. */
const val SPL_2022_REFUSAL = "Tokens in the Token-2022 program can be shown but not sent yet."

/** The reason a send is refused when the recipient's token account address holds an account that the token program does not own. */
const val RECIPIENT_NOT_TOKEN_ACCOUNT = "The recipient's token account address belongs to another program, so the send cannot go through."

/**
 * A send of an SPL token from one Solana account. It is planned as a SOL send is: a legacy message that spends from the account's nonce account, so
 * Lensly can sign it offline, and it is signed and broadcast by the same functions. Lumio holds no key.
 */
object SolanaSplSend {
    /**
     * Whether the recipient already has a token account for the mint, from the account read at its address: null when no account is there. Throws
     * IllegalArgumentException, with the owner's words, when an account is there that the token program does not own. Such an account blocks the
     * new token account, so the send would fail on the network after it is signed.
     */
    fun recipientAccountExists(account: SolanaAccountState?): Boolean {
        if (account == null) return false
        require(account.owner == SolanaTokenAccounts.TOKEN_PROGRAM) { RECIPIENT_NOT_TOKEN_ACCOUNT }
        return true
    }

    /**
     * The send of [amount] base units of the token in [balance] from [account] to [recipient]. [sol] is the account's SOL balance, and [nonceValue] the
     * nonce that its nonce account holds. [recipientAccountExists] says whether the recipient already has a token account for the mint. When it does
     * not, the send opens one, paid by the account at [rentLamports]. Throws IllegalArgumentException with the owner's words when the token is in
     * Token-2022, the amount is not positive or more than the account holds, or the SOL cannot pay the fee, the rent reserve and any new account.
     */
    fun plan(
        network: NetworkId,
        account: SolanaSendAccount,
        recipient: ByteArray,
        balance: SplBalance,
        amount: ULong,
        sol: BigInteger,
        nonceValue: ByteArray,
        requestId: ByteArray,
        recipientAccountExists: Boolean,
        rentLamports: Long,
        priority: Boolean = false,
    ): SolanaSendPlan {
        require(balance.program == SolanaTokenAccounts.TOKEN_PROGRAM) { SPL_2022_REFUSAL }
        require(amount > 0UL) { "The amount must be more than 0." }
        require(balance.amount >= BigInteger(amount.toString())) { "This account does not hold enough of this token." }
        val rent = if (recipientAccountExists) 0L else rentLamports
        // The units the priority fee pays for: the nonce advance, the token transfer, and the token account's creation when it is made.
        val units = SolanaPriorityFee.SYSTEM_UNITS + SolanaPriorityFee.PROGRAM_UNITS + if (recipientAccountExists) 0L else SolanaPriorityFee.PROGRAM_UNITS
        val priorityFee = if (priority) SolanaPriorityFee.lamports(units) else 0L
        val needed = BigInteger.valueOf(SOLANA_FEE_LAMPORTS + priorityFee + SOLANA_RENT_RESERVE_LAMPORTS + rent)
        require(sol >= needed) { "This account does not hold enough SOL for the fee, the rent reserve and any new token account." }
        return SolanaSendPlan(
            network = network,
            account = account,
            recipient = recipient,
            lamports = 0L,
            feeLamports = SOLANA_FEE_LAMPORTS,
            priorityFeeLamports = priorityFee,
            blockhash = nonceValue,
            requestId = requestId,
            message = SolanaSplTransfer.message(
                account = account.key,
                recipient = recipient,
                mint = Base58.decode(balance.mint),
                amount = amount,
                decimals = balance.decimals,
                nonceValue = nonceValue,
                createRecipientAccount = !recipientAccountExists,
                priority = priority,
            ),
            token = SplSendToken(balance.mint, balance.decimals, amount),
        )
    }
}
