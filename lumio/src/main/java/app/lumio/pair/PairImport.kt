package app.lumio.pair

import app.lensly.app.AddressBookEntry
import app.lensly.app.canonicalAddressOf
import app.lensly.app.evmAddressOf
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.byteVector32

/** A code that Lumio refuses. The message is shown to the owner as it is. */
class PairingRefusal(message: String) : IllegalArgumentException(message)

/**
 * Reads the pairing that Lensly exports: one `ur:x-airvault-pairing` with the keys and the names of every App of a wallet (see
 * [PairingCodec]). The codec refuses a code of another layout version, so Lumio reads only the current one. Lumio checks that every key
 * has the shape its App gives it, then the store replaces what it holds for the wallet.
 */
object PairImport {
    /** The refusal of a code that is not a public key, such as a seed or a private key. */
    const val PRIVATE_KEY_REFUSAL = "The code holds private key material. Only public exports can be paired."

    /** The Apps Lumio watches. Another App in a pairing is left out, because Lumio cannot show its accounts. */
    const val ETHEREUM = "ethereum"
    const val BITCOIN = "bitcoin"
    const val SOLANA = "solana"
    const val ALGORAND = "algorand"

    /** The most Ethereum accounts a wallet keeps in Lumio: one account record each, and the folder holds this many. */
    const val MAX_ETHEREUM_ACCOUNTS = 100

    /** The most Bitcoin keys a wallet keeps in Lumio: its Bitcoin record holds this many. */
    const val MAX_BITCOIN_KEYS = 96

    /** The most Solana accounts a wallet keeps in Lumio: its Solana record holds this many. */
    const val MAX_SOLANA_ACCOUNTS = 100

    /** The most Algorand accounts a wallet keeps in Lumio: its Algorand record holds this many. */
    const val MAX_ALGORAND_ACCOUNTS = 100

    /** The UR type of the pairing before 2.9, which Lumio refuses. */
    private const val OLD_UR_TYPE = "airvault-pairing"

    private const val NOT_A_PAIRING = "This is not a pairing code from Lensly."
    private const val NO_ETHEREUM = "The code holds no Ethereum account key."
    private const val NOT_ACCOUNT_KEY = "The code's Ethereum key is not the address of its account, m/44'/60'/N'/0/0."
    private const val NOT_BITCOIN_KEY = "The code's Bitcoin key is not an account key: a compressed public key and its chain code, on a Bitcoin account path."
    private const val BAD_KEY = "The code's public key is not valid."
    private const val BAD_ADDRESS = "The code's Address Book holds an address that is not a valid Ethereum or Bitcoin address."
    private const val NOT_SOLANA_KEY = "The code's Solana key is not the key of its account, m/44'/501'/N'."
    private const val NOT_ALGORAND_KEY = "The code's Algorand key is not the key of its account, m/44'/283'/N'/0/0."
    private const val EVM_ADDRESS_BYTES = 20
    private const val ED25519_KEY_BYTES = 32
    private const val COMPRESSED_KEY_BYTES = 33
    private const val CHAIN_CODE_BYTES = 32

    /** A Bitcoin account's path has three levels, m/purpose'/coin'/account'. */
    private const val BITCOIN_PATH_LENGTH = 3

    /**
     * The pairing that [ur] carries, checked for Lumio.
     * @throws PairingRefusal with the reason, when [ur] is not a pairing, holds private key material, or breaks a rule.
     */
    fun of(ur: UR): PairingPayload = when (ur.type.lowercase()) {
        PairingCodec.UR_TYPE -> checked(ur)
        // The type before 2.9 is a hard switch: its codes are refused, and the owner pairs again.
        OLD_UR_TYPE -> throw PairingRefusal(PairingCodec.OLDER_CODE)
        // A seed is private material, whatever the owner meant to export, so it gets the private-key refusal.
        "crypto-seed" -> throw PairingRefusal(PRIVATE_KEY_REFUSAL)
        else -> throw PairingRefusal("$NOT_A_PAIRING ur:${ur.type} is not supported.")
    }

    private fun checked(ur: UR): PairingPayload {
        val payload = try {
            PairingCodec.fromUr(ur, BrotliDecoder)
        } catch (e: IllegalArgumentException) {
            throw PairingRefusal(e.message ?: NOT_A_PAIRING)
        }
        val ethereum = payload.apps.firstOrNull { it.app == ETHEREUM }?.takeIf { it.accounts.isNotEmpty() } ?: throw PairingRefusal(NO_ETHEREUM)
        checkAccounts(ethereum, "Ethereum", MAX_ETHEREUM_ACCOUNTS, EVM_ADDRESS_BYTES, NOT_ACCOUNT_KEY, ::ethereumPathOf)
        payload.apps.firstOrNull { it.app == BITCOIN }?.let { checkBitcoin(payload.fingerprint, it) }
        payload.apps.firstOrNull { it.app == SOLANA }?.let { checkAccounts(it, "Solana", MAX_SOLANA_ACCOUNTS, ED25519_KEY_BYTES, NOT_SOLANA_KEY, ::solanaPathOf) }
        payload.apps.firstOrNull { it.app == ALGORAND }?.let {
            checkAccounts(it, "Algorand", MAX_ALGORAND_ACCOUNTS, ED25519_KEY_BYTES, NOT_ALGORAND_KEY, ::algorandPathOf)
        }
        return payload.copy(addressBook = addressBookOf(payload.addressBook))
    }

    /**
     * The Address Book entries with each address in its canonical form. An entry whose address is neither a valid Ethereum nor a valid
     * Bitcoin address refuses the whole code, so Lumio never keeps a name for an address it cannot read. The count is bounded by the codec.
     */
    private fun addressBookOf(book: List<AddressBookEntry>): List<AddressBookEntry> = book.map { entry ->
        entry.copy(address = canonicalAddressOf(entry.address) ?: throw PairingRefusal(BAD_ADDRESS))
    }

    /**
     * The keys of an App whose account N holds [keyBytes] bytes of key at the path [pathOf] gives for N, with no chain code. The count is
     * bounded, and the accounts are numbered 0 to n-1 by the codec.
     */
    private fun checkAccounts(app: PairingApp, name: String, max: Int, keyBytes: Int, refusal: String, pathOf: (Int) -> List<Long>) {
        if (app.accounts.size > max) throw PairingRefusal("The code carries ${app.accounts.size} $name accounts. Lumio pairs up to $max.")
        app.keys.forEachIndexed { account, key ->
            if (key.path != pathOf(account) || key.material.size != keyBytes || key.chainCode != null) throw PairingRefusal(refusal)
        }
    }

    /** Each Bitcoin key is the compressed key and chain code of a Bitcoin account, with a receive address that the key derives. */
    private fun checkBitcoin(fingerprint: Long, app: PairingApp) {
        if (app.keys.size > MAX_BITCOIN_KEYS) {
            throw PairingRefusal("The code carries ${app.keys.size} Bitcoin accounts. Lumio pairs up to $MAX_BITCOIN_KEYS.")
        }
        if (app.keys.map { it.path }.distinct().size != app.keys.size) {
            throw PairingRefusal("The code lists one Bitcoin account more than once.")
        }
        for (key in app.keys) {
            val account = key.toBitcoinAccount(fingerprint) ?: throw PairingRefusal(NOT_BITCOIN_KEY)
            checkKey { account.receiveAddress(0) }
        }
    }

    /** The Ethereum address of each account of a validated pairing, in account order: the address of account N is at index N. */
    internal fun ethereumAddressesOf(payload: PairingPayload): List<String> {
        val app = requireNotNull(payload.apps.firstOrNull { it.app == ETHEREUM }) { NO_ETHEREUM }
        return app.keys.map { key -> requireNotNull(evmAddressOf("0x" + key.material.byteVector().toHex())) { NOT_ACCOUNT_KEY } }
    }

    /** The public key of each Solana account of a validated pairing, in account order: the key of account N is at index N. */
    internal fun solanaPublicKeysOf(payload: PairingPayload): List<ByteArray> =
        payload.apps.firstOrNull { it.app == SOLANA }?.keys?.map { it.material }.orEmpty()

    /** The public key of each Algorand account of a validated pairing, in account order: the key of account N is at index N. */
    internal fun algorandPublicKeysOf(payload: PairingPayload): List<ByteArray> =
        payload.apps.firstOrNull { it.app == ALGORAND }?.keys?.map { it.material }.orEmpty()

    /** The watch-only Bitcoin accounts of a validated pairing, each on its network and Script Type, with the fingerprint of the wallet. */
    internal fun bitcoinAccountsOf(payload: PairingPayload): List<BtcWatchOnlyAccount> {
        val keys = payload.apps.firstOrNull { it.app == BITCOIN }?.keys ?: emptyList()
        return keys.map { key ->
            requireNotNull(key.toBitcoinAccount(payload.fingerprint)) { "A validated Bitcoin key has a Bitcoin account path." }
        }
    }

    /**
     * The watch-only Bitcoin account of this key, or null when it is not a key of a Bitcoin account of a network and Script Type that Lumio
     * knows. The pairing does not carry the parent fingerprint of the key, so the account's xpub has none (zero): no Lumio path reads it.
     */
    private fun PairingKey.toBitcoinAccount(fingerprint: Long): BtcWatchOnlyAccount? {
        val code = chainCode ?: return null
        if (material.size != COMPRESSED_KEY_BYTES || code.size != CHAIN_CODE_BYTES || path.size != BITCOIN_PATH_LENGTH) return null
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
                publickeybytes = material.byteVector(),
                chaincode = code.byteVector32(),
                depth = BITCOIN_PATH_LENGTH,
                path = KeyPath(path),
                parent = 0L,
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

    /** The path of the key of account [account]: m/[purpose]'/[coin]'/[account]', followed by [suffix]. */
    private fun accountPath(purpose: Long, coin: Long, account: Int, vararg suffix: Long): List<Long> =
        listOf(DeterministicWallet.hardened(purpose), DeterministicWallet.hardened(coin), DeterministicWallet.hardened(account.toLong())) +
            suffix.toList()

    /** The path of the address key of Ethereum account [account]: m/44'/60'/[account]'/0/0. */
    private fun ethereumPathOf(account: Int): List<Long> = accountPath(44, 60, account, 0, 0)

    /** The path of the Solana key of account [account]: m/44'/501'/[account]'. */
    private fun solanaPathOf(account: Int): List<Long> = accountPath(44, 501, account)

    /** The path of the Algorand key of account [account]: m/44'/283'/[account]'/0/0. */
    private fun algorandPathOf(account: Int): List<Long> = accountPath(44, 283, account, 0, 0)
}
