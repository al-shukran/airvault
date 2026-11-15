package app.lumio.pair

import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.CryptoHdKeyDecoder
import app.lensly.core.ur.PRIVATE_KEY_REFUSAL
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.byteVector32

/** A code that Lumio refuses. The message is shown to the owner as it is. */
class PairingRefusal(message: String) : IllegalArgumentException(message)

/**
 * Reads the pairing that Lensly exports: one `ur:airvault-pairing` with the keys and the names of every App of a wallet (see
 * [PairingCodec]). Lumio checks that every key is public and is a key it watches, then the store replaces what it holds for the wallet.
 * A code of an older Lensly, which holds fewer keys and no names, is refused, so it never replaces a wallet's names with fewer of them.
 */
object PairImport {
    /** The Apps Lumio watches. Another App in a pairing is left out, because Lumio cannot show its accounts. */
    const val ETHEREUM = "ethereum"
    const val BITCOIN = "bitcoin"

    /** The most Ethereum accounts a wallet keeps in Lumio: one account record each, and the folder holds this many. */
    const val MAX_ETHEREUM_ACCOUNTS = 100

    /** The most Bitcoin keys a wallet keeps in Lumio: its Bitcoin record holds this many. */
    const val MAX_BITCOIN_KEYS = 96

    private const val NOT_A_PAIRING = "This is not a pairing code from Lensly."
    private const val OLDER_CODE =
        "This code is from an older Lensly, which does not carry the names of the accounts. Pair the wallet again from Lensly's Pair wallet."
    private const val NO_ETHEREUM = "The code holds no Ethereum account key."
    private const val NOT_ACCOUNT_KEY = "The code's Ethereum key is not the account key m/44'/60'/0'."
    private const val BAD_KEY = "The code's public key is not valid."

    /**
     * The pairing that [ur] carries, checked for Lumio.
     * @throws PairingRefusal with the reason, when [ur] is not a pairing, holds an unknown version or private key material, or breaks a rule.
     */
    fun of(ur: UR): PairingPayload = when (ur.type.lowercase()) {
        PairingCodec.UR_TYPE -> checked(ur)
        // An older Lensly's code: refused, with the reason it has when it holds private material or is not a public key.
        "crypto-hdkey" -> throw olderCodeRefusal { CryptoHdKeyDecoder.accountKey(ur) }
        "crypto-account" -> throw olderCodeRefusal { BtcWatchOnly.parseCryptoAccount(ur) }
        // A seed is private material, whatever the owner meant to export, so it gets the private-key refusal.
        "crypto-seed" -> throw PairingRefusal(PRIVATE_KEY_REFUSAL)
        else -> throw PairingRefusal("$NOT_A_PAIRING ur:${ur.type} is not supported.")
    }

    private fun checked(ur: UR): PairingPayload {
        val payload = try {
            PairingCodec.fromUr(ur)
        } catch (e: IllegalArgumentException) {
            throw PairingRefusal(e.message ?: NOT_A_PAIRING)
        }
        checkEthereum(payload.apps.firstOrNull { it.app == ETHEREUM } ?: throw PairingRefusal(NO_ETHEREUM))
        payload.apps.firstOrNull { it.app == BITCOIN }?.let { checkBitcoin(payload.fingerprint, it) }
        return payload
    }

    /** One account key, on the Ethereum account path, with the accounts numbered 0 to n-1 under it. */
    private fun checkEthereum(app: PairingApp) {
        val key = app.keys.singleOrNull() ?: throw PairingRefusal(NO_ETHEREUM)
        if (key.path != ETHEREUM_PATH || key.depth != ETHEREUM_DEPTH) throw PairingRefusal(NOT_ACCOUNT_KEY)
        if (app.accounts.isEmpty()) throw PairingRefusal(NO_ETHEREUM)
        if (app.accounts.size > MAX_ETHEREUM_ACCOUNTS) {
            throw PairingRefusal("The code carries ${app.accounts.size} Ethereum accounts. Lumio pairs up to $MAX_ETHEREUM_ACCOUNTS.")
        }
        val children = app.accounts.map { it.child ?: throw PairingRefusal("An Ethereum account has no address index.") }
        if (children.sorted() != children.indices.toList()) throw PairingRefusal("The code's Ethereum accounts are not numbered 0 to n-1.")
        checkKey { AccountAddresses.addressAt(key.toEthereumXpub(), 0) }
    }

    /** Each Bitcoin key on a Bitcoin account path, once, with a receive address that the key derives. */
    private fun checkBitcoin(fingerprint: Long, app: PairingApp) {
        if (app.keys.size > MAX_BITCOIN_KEYS) {
            throw PairingRefusal("The code carries ${app.keys.size} Bitcoin accounts. Lumio pairs up to $MAX_BITCOIN_KEYS.")
        }
        if (app.keys.map { it.path }.distinct().size != app.keys.size) {
            throw PairingRefusal("The code lists one Bitcoin account more than once.")
        }
        for (key in app.keys) {
            val account = key.toBitcoinAccount(fingerprint) ?: throw PairingRefusal("The code's path is not a Bitcoin account path.")
            checkKey { account.receiveAddress(0) }
        }
    }

    /** The Ethereum account key of a validated pairing, the key every Ethereum account derives from. */
    internal fun ethereumKeyOf(payload: PairingPayload): AccountXpub {
        val key = requireNotNull(payload.apps.firstOrNull { it.app == ETHEREUM }?.keys?.singleOrNull()) { NO_ETHEREUM }
        return key.toEthereumXpub()
    }

    /** The watch-only Bitcoin accounts of a validated pairing, each on its network and Script Type, with the fingerprint of the wallet. */
    internal fun bitcoinAccountsOf(payload: PairingPayload): List<BtcWatchOnlyAccount> {
        val keys = payload.apps.firstOrNull { it.app == BITCOIN }?.keys ?: emptyList()
        return keys.map { key ->
            requireNotNull(key.toBitcoinAccount(payload.fingerprint)) { "A validated Bitcoin key has a Bitcoin account path." }
        }
    }

    private fun PairingKey.toEthereumXpub(): AccountXpub = AccountXpub(
        publicKey = publicKey,
        chainCode = chainCode,
        parentFingerprint = parentFingerprint.toInt(),
    )

    /** The watch-only Bitcoin account of this key, or null when its path is not m/purpose'/coin'/account'. */
    private fun PairingKey.toBitcoinAccount(fingerprint: Long): BtcWatchOnlyAccount? {
        if (path.size != 3 || !DeterministicWallet.isHardened(path[2])) return null
        val (network, scriptType) = BtcNetwork.entries.flatMap { network -> ScriptType.entries.map { network to it } }
            .firstOrNull { (network, scriptType) ->
                path[0] == DeterministicWallet.hardened(scriptType.purpose.toLong()) &&
                    path[1] == DeterministicWallet.hardened(network.coinType.toLong())
            } ?: return null
        return BtcWatchOnlyAccount(
            network = network,
            scriptType = scriptType,
            masterFingerprint = fingerprint,
            path = KeyPath(path),
            xpub = DeterministicWallet.ExtendedPublicKey(
                publickeybytes = publicKey.byteVector(),
                chaincode = chainCode.byteVector32(),
                depth = depth,
                path = KeyPath(path),
                parent = parentFingerprint,
            ),
        )
    }

    /** Runs [derive], which fails for a point that is not on the curve, and refuses the code instead of crashing. */
    private inline fun checkKey(derive: () -> Unit) {
        try {
            derive()
        } catch (e: Exception) {
            throw PairingRefusal(BAD_KEY)
        }
    }

    /** The refusal of an older code: the reason of its own decoder when it is invalid, and [OLDER_CODE] when it is valid but old. */
    private inline fun olderCodeRefusal(decode: () -> Any): PairingRefusal {
        try {
            decode()
        } catch (e: IllegalArgumentException) {
            return PairingRefusal(e.message ?: NOT_A_PAIRING)
        }
        return PairingRefusal(OLDER_CODE)
    }

    private val ETHEREUM_PATH: List<Long> = listOf(
        DeterministicWallet.hardened(44),
        DeterministicWallet.hardened(60),
        DeterministicWallet.hardened(0),
    )
    private const val ETHEREUM_DEPTH = 3
}
