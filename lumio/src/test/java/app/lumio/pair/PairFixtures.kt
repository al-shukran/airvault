package app.lumio.pair

import app.lensly.app.export.PairingPayload
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
