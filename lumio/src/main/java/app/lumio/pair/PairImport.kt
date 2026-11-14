package app.lumio.pair

import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.ur.CryptoHdKeyDecoder
import app.lensly.core.ur.PRIVATE_KEY_REFUSAL
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.DeterministicWallet

/** A code that Lumio refuses. The message is shown to the owner as it is. */
class PairingRefusal(message: String) : IllegalArgumentException(message)

/**
 * A pairing code that Lensly exported, read and checked. It holds public data only: the account key of an EVM wallet, or the watch-only
 * Accounts of a Bitcoin wallet. [fingerprint] is the master fingerprint of the seed, as an unsigned 32-bit value.
 */
sealed interface Pairing {
    val fingerprint: Long

    /** The wallet's name as the code carries it, or null when it carries none. */
    val name: String?

    /** The Ethereum account key m/44'/60'/0'. [name] is the wallet's name in Lensly, or null when the code carries none. */
    data class Evm(override val fingerprint: Long, override val name: String?, val xpub: AccountXpub) : Pairing

    /** The Bitcoin Accounts of one or more networks, one per Script Type. The export carries no name. */
    data class Bitcoin(override val fingerprint: Long, val accounts: List<BtcWatchOnlyAccount>) : Pairing {
        override val name: String? get() = null
    }
}

/**
 * Reads a complete pairing UR from Lensly's exports: `ur:crypto-hdkey` for the Ethereum account key, and `ur:crypto-account` for the
 * Bitcoin watch-only Accounts. Any other UR type is refused, and so is any key that is not a public key.
 */
object PairImport {
    /** The longest wallet name a pairing carries, in UTF-8 bytes. It fits the wallet's META record. */
    const val MAX_NAME_BYTES = 64

    /** Two networks and four Script Types: the most Accounts one export carries. */
    const val MAX_BITCOIN_ACCOUNTS = 8

    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val NOT_A_PAIRING = "This is not a Lensly pairing code."
    private const val BAD_KEY = "The code's public key is not valid."

    /**
     * The pairing that [ur] carries.
     * @throws PairingRefusal with the reason, when [ur] is not a pairing or holds anything other than public data.
     */
    fun of(ur: UR): Pairing = when (ur.type.lowercase()) {
        "crypto-hdkey" -> evm(ur)
        "crypto-account" -> bitcoin(ur)
        // A seed is private material, whatever the owner meant to export, so it gets the private-key refusal.
        "crypto-seed" -> throw PairingRefusal(PRIVATE_KEY_REFUSAL)
        else -> throw PairingRefusal("$NOT_A_PAIRING ur:${ur.type} is not supported.")
    }

    private fun evm(ur: UR): Pairing.Evm {
        val account = try {
            CryptoHdKeyDecoder.accountKey(ur)
        } catch (e: IllegalArgumentException) {
            throw PairingRefusal(e.message ?: NOT_A_PAIRING)
        }
        checkKey { AccountAddresses.addressAt(account.xpub, 0) }
        return Pairing.Evm(
            fingerprint = account.masterFingerprint.toLong() and UINT32_MASK,
            name = nameOf(account.name),
            xpub = account.xpub,
        )
    }

    private fun bitcoin(ur: UR): Pairing.Bitcoin {
        val accounts = try {
            BtcWatchOnly.parseCryptoAccount(ur)
        } catch (e: IllegalArgumentException) {
            throw PairingRefusal(e.message ?: NOT_A_PAIRING)
        }
        if (accounts.isEmpty() || accounts.size > MAX_BITCOIN_ACCOUNTS) {
            throw PairingRefusal("The code carries ${accounts.size} Bitcoin accounts. A pairing carries 1 to $MAX_BITCOIN_ACCOUNTS.")
        }
        if (accounts.map { it.network to it.scriptType }.distinct().size != accounts.size) {
            throw PairingRefusal("The code lists one Bitcoin account more than once.")
        }
        for (account in accounts) {
            if (!isAccountPath(account)) throw PairingRefusal("The code's path is not a Bitcoin account path.")
            checkKey { account.receiveAddress(0) }
        }
        return Pairing.Bitcoin(fingerprint = accounts.first().masterFingerprint, accounts = accounts)
    }

    /** m/purpose'/coin'/account' with the purpose of the Account's Script Type and the coin of its network. */
    private fun isAccountPath(account: BtcWatchOnlyAccount): Boolean {
        val path = account.path.path
        return path.size == 3 &&
            path[0] == DeterministicWallet.hardened(account.scriptType.purpose.toLong()) &&
            path[1] == DeterministicWallet.hardened(account.network.coinType.toLong()) &&
            DeterministicWallet.isHardened(path[2])
    }

    /** Runs [derive], which fails for a point that is not on the curve, and refuses the code instead of crashing. */
    private inline fun checkKey(derive: () -> Unit) {
        try {
            derive()
        } catch (e: Exception) {
            throw PairingRefusal(BAD_KEY)
        }
    }

    /** The name without its edges, or null when there is none. A name is plain text that fits the record. */
    private fun nameOf(raw: String?): String? {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name.any { it.isISOControl() }) throw PairingRefusal("The code's name is not plain text.")
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            throw PairingRefusal("The code's name is longer than $MAX_NAME_BYTES bytes.")
        }
        return name
    }
}
