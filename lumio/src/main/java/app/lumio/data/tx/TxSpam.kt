package app.lumio.data.tx

import app.lumio.data.net.NetworkId
import java.math.BigInteger

/** Why a transaction is flagged as spam. Each reason is one pattern of [TxSpam]. */
enum class SpamReason { ZERO_VALUE, DUST, LOOKALIKE, NAME }

/**
 * The spam heuristic. It judges only the ERC-20 transfers of tokens that the registry does not list, which [isKnownToken] answers. A native coin and a
 * listed token are never flagged. A transfer of an unlisted token is flagged when it:
 * - moves no value ([SpamReason.ZERO_VALUE]), or less than one millionth of a whole token ([SpamReason.DUST]);
 * - is received from an address that looks like one the account has dealt with: the same first four and last four hex digits as a known address, but a
 *   different address ([SpamReason.LOOKALIKE]). This is the address-poisoning pattern: it sends from a lookalike and waits for the holder to copy the
 *   address from the history;
 * - has a name or a symbol with a link or a call to action, "claim" or "visit" ([SpamReason.NAME]).
 *
 * [knownCounterparties] are the addresses that the account has dealt with, and the caller reads them from the store, never from the transaction itself.
 */
class TxSpam(private val isKnownToken: (NetworkId, String) -> Boolean) {
    /** The reasons that flag [tx]. The set is empty when the transaction is not spam. */
    fun reasons(tx: LumioTx, knownCounterparties: Set<String>): Set<SpamReason> {
        val contract = tx.asset.contract ?: return emptySet()
        if (isKnownToken(tx.network, contract)) return emptySet()
        return buildSet {
            if (tx.amount.signum() == 0) {
                add(SpamReason.ZERO_VALUE)
            } else if (isDust(tx.amount, tx.asset.decimals)) {
                add(SpamReason.DUST)
            }
            val sender = tx.counterparty
            if (tx.direction == TxDirection.IN && sender != null && isLookalike(sender, knownCounterparties)) add(SpamReason.LOOKALIKE)
            if (NAME.containsMatchIn(tx.asset.name) || NAME.containsMatchIn(tx.asset.symbol)) add(SpamReason.NAME)
        }
    }

    /** True when [reasons] is not empty. */
    fun isSpam(tx: LumioTx, knownCounterparties: Set<String>): Boolean = reasons(tx, knownCounterparties).isNotEmpty()

    companion object {
        private val DUST_SCALE: BigInteger = BigInteger.valueOf(1_000_000)
        private val NAME = Regex(
            "https?://|www\\.|\\.(com|io|net|org|xyz|app|finance|click|link|top|site|vip|info)\\b|claim|visit",
            RegexOption.IGNORE_CASE,
        )

        /** True when [amount] of a coin with [decimals] decimals is less than one millionth of a whole unit. */
        fun isDust(amount: BigInteger, decimals: Int): Boolean = amount.multiply(DUST_SCALE) < BigInteger.TEN.pow(decimals)

        /** True when [sender] is not one of [known], and it shares its first four and last four hex digits with one of them. */
        fun isLookalike(sender: String, known: Set<String>): Boolean = known.any { k ->
            k != sender && k.length == sender.length && k.take(LOOKALIKE_PREFIX) == sender.take(LOOKALIKE_PREFIX) &&
                k.takeLast(LOOKALIKE_SUFFIX) == sender.takeLast(LOOKALIKE_SUFFIX)
        }

        /** `0x` and four hex digits. */
        private const val LOOKALIKE_PREFIX = 6

        private const val LOOKALIKE_SUFFIX = 4
    }
}
