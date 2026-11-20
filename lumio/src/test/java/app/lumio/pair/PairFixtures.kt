package app.lumio.pair

import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.core.model.Curve
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingPayload
import app.lensly.app.export.accountSteps
import app.lensly.app.export.keyExport
import app.lensly.app.export.watchOnlyExportOf
import app.lensly.app.autoAccountName
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex

/** Seeds of two public 24-word BIP39 test vectors. They hold no funds, and the tests derive every key from them. */
internal object PairFixtures {
    /** The 24-word vector of 256 zero bits. */
    private val ABANDON = "abandon ".repeat(23) + "art"

    /** The 24-word vector of 256 one bits. */
    private val ZOO = "zoo ".repeat(23) + "vote"

    val seed: ByteArray = seedOf(ABANDON)
    val otherSeed: ByteArray = seedOf(ZOO)

    /** The EIP-55 address of account [account] of [seed], m/44'/60'/[account]'/0/0, as a pairing carries it. */
    fun address(seed: ByteArray, account: Int): String = AccountAddresses.addressOfKey(AccountAddresses.addressXpub(HdWallet(seed).accountXpub(account), 0))

    /** The first [count] paired Ethereum accounts of [seed], with no name, label or group. */
    fun evmAccounts(count: Int = 1, seed: ByteArray = PairFixtures.seed): List<PairedAccount> =
        (0 until count).map { PairedAccount(it, address(seed, it), null, null, null) }

    /** The watch-only Account of [seed] for [network] and [scriptType], at account index 0. */
    fun account(seed: ByteArray, network: BtcNetwork, scriptType: ScriptType): BtcWatchOnlyAccount =
        BtcAccount.fromSeed(seed, network, scriptType).watchOnly()

    /** All eight Accounts of [seed]: both networks, each with the four Script Types. */
    fun allAccounts(seed: ByteArray): List<BtcWatchOnlyAccount> = BtcNetwork.entries.flatMap { network ->
        ScriptType.entries.map { scriptType -> account(seed, network, scriptType) }
    }

    /** The master fingerprint of [seed], which a pairing names for each of its accounts. */
    fun fingerprint(seed: ByteArray): Int = HdWallet(seed).masterFingerprint

    /** The compressed key of Ethereum account [account] of [seed], m/44'/60'/[account]'/0/0, as a pairing carries it. */
    fun ethereumKey(seed: ByteArray, account: Int): ByteArray = AccountAddresses.addressXpub(HdWallet(seed).accountXpub(account), 0).publicKey

    /** Ethereum account [index] of [seed], named [name], with its label and group, as Lensly exports it. */
    fun ethereumAccount(index: Int, name: String, label: String? = null, group: String? = null, seed: ByteArray = PairFixtures.seed): PairingAccount =
        PairingAccount(keyExport(ethereumMainnet, fingerprint(seed), accountSteps(44, 60, index, 0, 0), Curve.SECP256K1, ethereumKey(seed, index)), name, label, group)

    /** The key of address [index] of account 0 of [seed], m/44'/60'/0'/0/[index]: an address key, which is not an Ethereum account of a pairing. */
    fun ethereumAddressAccount(index: Int, name: String, seed: ByteArray = PairFixtures.seed): PairingAccount = PairingAccount(
        keyExport(ethereumMainnet, fingerprint(seed), accountSteps(44, 60, 0, 0, index), Curve.SECP256K1, AccountAddresses.addressXpub(HdWallet(seed).accountXpub(0), index).publicKey),
        name, null, null,
    )

    /** Solana account [index] of [seed], named [name]. */
    fun solanaAccount(index: Int, name: String, seed: ByteArray = PairFixtures.seed): PairingAccount = PairingAccount(
        keyExport(SOLANA_MAINNET, fingerprint(seed), accountSteps(44, 501, index), Curve.ED25519_SLIP10, SolanaAccount.publicKey(seed, index)),
        name, null, null,
    )

    /** Algorand account [index] of [seed], named [name]. */
    fun algorandAccount(index: Int, name: String, seed: ByteArray = PairFixtures.seed): PairingAccount = PairingAccount(
        keyExport(algorandMainnet, fingerprint(seed), accountSteps(44, 283, index, 0, 0), Curve.ED25519_BIP32, AlgorandAccount.publicKey(seed, index)),
        name, null, null,
    )

    /** The Bitcoin [account] as Lensly exports it, named [name], with its label and group. */
    fun bitcoinAccount(account: BtcWatchOnlyAccount, name: String, label: String? = null, group: String? = null): PairingAccount =
        PairingAccount(watchOnlyExportOf(account, account.masterFingerprint.toInt()), name, label, group)

    /** The Bitcoin accounts of [seed] (all eight), each named by its Auto-Name of the position it has in the pairing, under [base]. */
    fun bitcoinAccounts(seed: ByteArray = PairFixtures.seed, base: String = "Demo"): List<PairingAccount> =
        allAccounts(seed).mapIndexed { position, account -> bitcoinAccount(account, autoAccountName(base, position)) }

    /** A pairing of [accounts] for the wallet of [seed], named [name], whose Auto-Name base is [base]. */
    fun payloadOf(accounts: List<PairingAccount>, seed: ByteArray = PairFixtures.seed, name: String = "Demo", base: String = "Demo"): PairingPayload =
        PairingPayload(fingerprint = fingerprint(seed).toLong() and 0xFFFFFFFFL, name = name, autoNameBase = base, accounts = accounts)

    private fun seedOf(phrase: String): ByteArray = Mnemonic.of(phrase.split(" ")).use { it.deriveSeed("") }
}

/** What a Bitcoin account is to Lumio: its place, its keys and its path. The parent fingerprint is not in the pairing, so it is left out. */
internal fun BtcWatchOnlyAccount.shape(): List<Any> =
    listOf(network, scriptType, path.path, xpub.publickeybytes.toByteArray().toHex(), xpub.chaincode.toByteArray().toHex())

/**
 * Feeds [parts] to a [PairScan] in order, as the camera would, and returns the step that ends the scan: the first Complete or Refused.
 * @throws IllegalStateException when the parts never complete a code.
 */
internal fun scanToEnd(parts: List<String>): ScanStep {
    val scan = PairScan()
    val steps = parts.map { scan.onText(it) }
    return steps.firstOrNull { it is ScanStep.Complete || it is ScanStep.Refused }
        ?: error("The scan never ended. The last step was ${steps.last()}.")
}

/** The pairing that [parts] complete. Fails the test when they refuse or do not complete. */
internal fun pairingOf(parts: List<String>): PairingPayload = (scanToEnd(parts) as? ScanStep.Complete)?.pairing
    ?: error("The code was refused: ${scanToEnd(parts)}")

/** What a pairing account carries: its chain, path, key, chain code, and names. The payload has no equality for keys, so tests compare these. */
internal fun PairingAccount.shape(): List<Any?> = listOf(
    export.chain,
    export.signer.path,
    export.publicKey.curve,
    export.publicKey.bytes.toHex(),
    export.chainCode?.toHex(),
    name,
    label,
    group,
)
