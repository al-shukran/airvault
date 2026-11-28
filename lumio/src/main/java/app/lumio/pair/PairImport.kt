package app.lumio.pair

import app.airvault.chains.evm.wallet.EvmWallet
import app.airvault.core.model.Capability
import app.airvault.core.model.ChainId
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.Path
import app.airvault.core.signer.allows
import app.lensly.app.AddressBookEntry
import app.lensly.app.canonicalAddressOf
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.PathStep
import app.lumio.chains.installedWallets
import app.lumio.chains.walletRegistry
import app.lumio.data.net.Networks
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.byteVector32

/** A code that Lumio refuses. The message is shown to the owner as it is. */
class PairingRefusal(message: String) : IllegalArgumentException(message)

/**
 * Reads the pairing that Lensly exports: one `ur:x-airvault-pairing` with the accounts of every chain of a wallet (see [PairingCodec]). The codec
 * refuses a code of another layout version, so Lumio reads only the current one. Each account is checked by the manifest and the address rules
 * of its chain, found in the registry of installed wallets, so a chain added to Lumio needs no change here. The store then replaces what it holds
 * for the wallet.
 */
object PairImport {
    /** The refusal of a code that is not a public key, such as a seed or a private key. */
    const val PRIVATE_KEY_REFUSAL = "The code holds private key material. Only public exports can be paired."

    /** The most Bitcoin keys a wallet keeps in Lumio: the same cap Lensly enforces, and its Bitcoin record holds this many. */
    const val MAX_BITCOIN_KEYS = PairingCodec.MAX_BITCOIN_ACCOUNTS

    /** The most Solana accounts a wallet keeps in Lumio: its Solana record holds this many. */
    const val MAX_SOLANA_ACCOUNTS = 100

    /** The most Algorand accounts a wallet keeps in Lumio: its Algorand record holds this many. */
    const val MAX_ALGORAND_ACCOUNTS = 100

    /** The most accounts a wallet keeps in Lumio, by the CAIP-2 namespace of their chain. */
    private val ACCOUNT_LIMITS = mapOf(
        Networks.EIP155 to PairingCodec.MAX_ETHEREUM_ACCOUNTS,
        Networks.BIP122 to MAX_BITCOIN_KEYS,
        Networks.SOLANA to MAX_SOLANA_ACCOUNTS,
        Networks.ALGORAND to MAX_ALGORAND_ACCOUNTS,
    )

    private const val NOT_A_PAIRING = "This is not a pairing code from Lensly."
    private const val NO_ACCOUNTS = "The code holds no account key."
    private const val NOT_IN_SCOPE = "The code's key is not a key of its chain: its path is outside the key scopes of the chain."
    private const val NOT_WATCH_ONLY = "The code's key does not match its chain: a chain code is for watch-only extended keys only."
    private const val NOT_ACCOUNT_KEY = "The code's watch-only key is not the key of an account: its path is m/purpose'/coin'/N'."
    private const val WRONG_COIN = "The code's key is on another network: its coin type is not the coin type of its chain."
    private const val NOT_CHAIN_ACCOUNT = "The code's key is not an account key of its chain: its path is not the chain's account path."
    private const val OUT_OF_ORDER = "The code's accounts on one chain are not numbered 0 to n-1 in order."
    private const val DUPLICATE = "The code lists one account more than once."
    private const val BAD_KEY = "The code's public key is not valid."
    private const val NAMES_TOO_LONG =
        "The code's names for Bitcoin, Solana and Algorand accounts are longer than Lumio keeps. Shorten some of them in Lensly, then pair again."
    private const val BAD_ADDRESS = "The code's Address Book holds an address that is not a valid address of a supported chain."
    private const val COMPRESSED_KEY_BYTES = 33
    private const val ED25519_KEY_BYTES = 32
    private const val CHAIN_CODE_BYTES = 32

    /** The levels of an account path, m/purpose'/coin'/account': a watch-only key is the key of an account at this depth. */
    private const val ACCOUNT_DEPTH = 3

    /** The mask of the 32 bits of a master fingerprint, which a signer's fingerprint holds as a signed Int. */
    private const val UINT32_MASK = 0xFFFF_FFFFL

    /**
     * The pairing that [ur] carries, checked for Lumio.
     * @throws PairingRefusal with the reason, when [ur] is not a pairing, holds private key material, or breaks a rule.
     */
    fun of(ur: UR): PairingPayload = when (ur.type.lowercase()) {
        PairingCodec.UR_TYPE -> checked(ur)
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
        // An account on a chain that no installed wallet serves is left out, because Lumio cannot show it.
        val accounts = payload.accounts.filter { walletRegistry.byChain(it.export.chain) != null }
        if (accounts.isEmpty()) throw PairingRefusal(NO_ACCOUNTS)
        checkAccounts(accounts)
        return payload.copy(accounts = accounts, addressBook = addressBookOf(payload.addressBook))
    }

    /**
     * The Address Book entries with each address in its canonical form. An entry whose address is not valid on any chain that
     * [canonicalAddressOf] accepts refuses the whole code, so Lumio never keeps a name for an address it cannot read. The count is bounded by the codec.
     */
    private fun addressBookOf(book: List<AddressBookEntry>): List<AddressBookEntry> = book.map { entry ->
        entry.copy(address = canonicalAddressOf(entry.address) ?: throw PairingRefusal(BAD_ADDRESS))
    }

    /**
     * Each chain's accounts are checked for the count Lumio keeps, for a repeated account, for the key rules of their chain (see [checkAccount]), and
     * for their numbering: the accounts of a chain with single keys are numbered 0 to n-1, since Lumio's records keep them by position.
     */
    private fun checkAccounts(accounts: List<PairingAccount>) {
        for (namespace in accounts.map { it.export.chain.namespace }.distinct()) {
            val count = accounts.count { it.export.chain.namespace == namespace }
            val max = requireNotNull(ACCOUNT_LIMITS[namespace]) { "An installed chain has an account limit." }
            if (count > max) throw PairingRefusal("The code carries $count ${nameOf(namespace)} accounts. Lumio pairs up to $max.")
        }
        val named = listOf(Networks.BIP122, Networks.SOLANA, Networks.ALGORAND)
        if (accounts.filter { it.export.chain.namespace in named }.sumOf { PairRecords.nameRoom(listOf(it.name)) } > PairRecords.MAX_NAME_ROOM) {
            throw PairingRefusal(NAMES_TOO_LONG)
        }
        if (accounts.map { it.export.chain to it.export.signer.path }.distinct().size != accounts.size) throw PairingRefusal(DUPLICATE)
        accounts.forEach { checkAccount(it.export) }
        accounts.filter { it.export.chainCode == null }.groupBy { shapeOf(it.export) }.values.forEach { group ->
            group.forEachIndexed { position, account ->
                if (account.export.signer.path[ACCOUNT_LEVEL].index != position) throw PairingRefusal(OUT_OF_ORDER)
            }
        }
    }

    /**
     * Checks one exported account against its chain's manifest and wallet: its path is in a key scope of the chain on the key's curve and names the
     * coin type of the chain's network, its key has the size of that curve and, on secp256k1, is a point of the curve, and a chain code is present
     * exactly when the chain exports watch-only extended keys. A watch-only key must have the chain's address rules; a single key must be an account
     * key of its chain, which the wallet's [WalletApp.accountFrom] states for each chain.
     */
    private fun checkAccount(export: AccountExport) {
        val wallet = requireNotNull(walletRegistry.byChain(export.chain)) { "A checked account has an installed chain." }
        val manifest = wallet.manifest
        val network = requireNotNull(manifest.networks.firstOrNull { it.chainId == export.chain }) { "A checked account has an installed network." }
        val curve = export.publicKey.curve
        val key = export.publicKey.bytes
        if (!manifest.keys.allows(curve, Path(export.signer.path))) throw PairingRefusal(NOT_IN_SCOPE)
        if (export.signer.path[COIN_LEVEL].index != network.slip44) throw PairingRefusal(WRONG_COIN)
        if (key.size != keyBytesOf(curve)) throw PairingRefusal(BAD_KEY)
        if (curve == Curve.SECP256K1 && !PublicKey(key).isValid()) throw PairingRefusal(BAD_KEY)
        val watchOnly = Capability.WATCH_ONLY_EXPORT in manifest.capabilities
        val chainCode = export.chainCode
        if (watchOnly != (chainCode != null)) throw PairingRefusal(NOT_WATCH_ONLY)
        if (chainCode != null && chainCode.size != CHAIN_CODE_BYTES) throw PairingRefusal(BAD_KEY)
        if (chainCode != null && export.signer.path.size != ACCOUNT_DEPTH) throw PairingRefusal(NOT_ACCOUNT_KEY)
        checkKey { wallet.addresses.fromPublicKey(export.chain, key, export.kind) }
        if (chainCode == null) {
            try {
                wallet.accountFrom(export)
            } catch (e: IllegalArgumentException) {
                throw PairingRefusal(NOT_CHAIN_ACCOUNT)
            }
        }
    }

    /** The length of a key on [curve]: a compressed secp256k1 key, or an Ed25519 key with no prefix. */
    private fun keyBytesOf(curve: Curve): Int = if (curve == Curve.SECP256K1) COMPRESSED_KEY_BYTES else ED25519_KEY_BYTES

    /** The chain, the purpose and coin type, and the suffix of an account's path: the accounts that share one numbering. */
    private fun shapeOf(export: AccountExport): Triple<ChainId, List<PathStep>, List<PathStep>> =
        Triple(export.chain, export.signer.path.take(ACCOUNT_LEVEL), export.signer.path.drop(ACCOUNT_LEVEL + 1))

    /** The name of the chains of [namespace] as a pairing names them, from the manifest of the wallet that serves them. */
    private fun nameOf(namespace: String): String =
        installedWallets.first { namespace in it.manifest.namespaces }.manifest.displayName

    /** The Ethereum address of each account of a validated pairing, in account order: the address of account N is at index N. */
    internal fun ethereumAddressesOf(payload: PairingPayload): List<String> =
        accountsOf(payload, Networks.EIP155).map { EvmWallet.addresses.fromPublicKey(it.export.chain, it.export.publicKey.bytes, it.export.kind) }

    /** The public key of each Solana account of a validated pairing, in account order: the key of account N is at index N. */
    internal fun solanaPublicKeysOf(payload: PairingPayload): List<ByteArray> = accountsOf(payload, Networks.SOLANA).map { it.export.publicKey.bytes }

    /** The public key of each Algorand account of a validated pairing, in account order: the key of account N is at index N. */
    internal fun algorandPublicKeysOf(payload: PairingPayload): List<ByteArray> = accountsOf(payload, Networks.ALGORAND).map { it.export.publicKey.bytes }

    /** The watch-only Bitcoin accounts of a validated pairing, each on its network and Script Type, with the fingerprint of the wallet. */
    internal fun bitcoinAccountsOf(payload: PairingPayload): List<BtcWatchOnlyAccount> =
        accountsOf(payload, Networks.BIP122).map { account ->
            requireNotNull(bitcoinAccountOf(account.export)) { "A validated Bitcoin account is a watch-only key." }
        }

    /** The accounts of a pairing on the chains of [namespace], in the order of the pairing. */
    internal fun accountsOf(payload: PairingPayload, namespace: String): List<PairingAccount> =
        payload.accounts.filter { it.export.chain.namespace == namespace }

    /**
     * The watch-only Bitcoin account of this export, or null when it is not a watch-only key of a network and Script Type that Lumio knows. The
     * pairing does not carry the parent fingerprint of the key, so the account's xpub has none (zero): no Lumio path reads it.
     */
    private fun bitcoinAccountOf(export: AccountExport): BtcWatchOnlyAccount? {
        val code = export.chainCode ?: return null
        val fingerprint = export.signer.fingerprint ?: return null
        val key = export.publicKey.bytes
        if (key.size != COMPRESSED_KEY_BYTES || code.size != CHAIN_CODE_BYTES || export.signer.path.size != ACCOUNT_DEPTH) return null
        val path = export.signer.path.map { step -> step.toKeyPathChild() }
        val (network, scriptType) = BtcNetwork.entries.flatMap { network -> ScriptType.entries.map { network to it } }
            .firstOrNull { (network, scriptType) ->
                path[0] == DeterministicWallet.hardened(scriptType.purpose.toLong()) &&
                    path[1] == DeterministicWallet.hardened(network.coinType.toLong())
            } ?: return null
        return BtcWatchOnlyAccount(
            network = network,
            scriptType = scriptType,
            masterFingerprint = fingerprint.toLong() and UINT32_MASK,
            path = KeyPath(path),
            xpub = DeterministicWallet.ExtendedPublicKey(
                publickeybytes = key.byteVector(),
                chaincode = code.byteVector32(),
                depth = ACCOUNT_DEPTH,
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

    /** A wallet's path step as the BIP32 child number it names: each step's index, with the hardened bit set on a hardened step. */
    private fun PathStep.toKeyPathChild(): Long = if (hardened) DeterministicWallet.hardened(index.toLong()) else index.toLong()

    /** The coin type level of a path, m/purpose'/coin'/account'. It names the network of a key. */
    private const val COIN_LEVEL = 1

    /** The account level of a path, m/purpose'/coin'/account'. */
    private const val ACCOUNT_LEVEL = 2
}
