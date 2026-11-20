package app.lensly.app.export

import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.core.model.Curve
import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.airvault.chains.evm.evmAddressOf
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import java.util.UUID
import kotlin.random.Random

/**
 * A realistic vault for the size check and the round trip: one wallet on four chains. It has five Ethereum accounts, three Solana and two
 * Algorand accounts, the four Bitcoin accounts the owner stores (two Script Types on mainnet and one on testnet4), and an Address Book of
 * twelve entries. Some accounts are named, labelled or grouped, and the rest keep their Auto-Name. Every key is derived from the public BIP39
 * test wallet, so Lumio accepts it, and the vault holds no real key.
 */
object RealisticPairing {
    private val WORDS = ("abandon ".repeat(23) + "art").split(" ")
    private const val WALLET = "Family vault"

    /** A Bitcoin account the owner stores: its network, Script Type and index, and the Display Name the owner gave it, or null for its Auto-Name. */
    private class Stored(val network: BtcNetwork, val scriptType: ScriptType, val index: Int, val name: String?)

    private val STORED = listOf(
        Stored(BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0, "Spending"),
        Stored(BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 1, null),
        Stored(BtcNetwork.MAINNET, ScriptType.TAPROOT, 0, null),
        Stored(BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0, null),
    )

    /** The pairing of the vault: its accounts on every chain, in the order of the chains, and the Address Book. */
    fun payload(): PairingPayload {
        val seed = Mnemonic.of(WORDS).use { it.deriveSeed("") }
        val wallet = HdWallet(seed)
        val fingerprint = wallet.masterFingerprint
        fun auto(index: Int) = autoAccountName(WALLET, index)
        fun ethereumKey(account: Int): ByteArray = AccountAddresses.addressXpub(wallet.accountXpub(account), 0).publicKey
        fun ethereumAddress(account: Int): String = AccountAddresses.addressOfKey(AccountAddresses.addressXpub(wallet.accountXpub(account), 0))

        val ethereumNames = listOf("Main", null, "Savings", null, null)
        val ethereum = ethereumNames.mapIndexed { i, name ->
            PairingAccount(
                export = keyExport(ethereumMainnet, fingerprint, accountSteps(44, 60, i, 0, 0), Curve.SECP256K1, ethereumKey(i)),
                name = name ?: auto(i),
                label = if (i == 0) "Cold" else null,
                group = if (i == 2) "Savings" else null,
            )
        }
        val stored = STORED.sortedWith(compareBy({ it.scriptType.purpose }, { it.network.coinType }, { it.index }))
        val bitcoin = stored.map { record ->
            PairingAccount(
                export = watchOnlyExportOf(BtcAccount.fromSeed(seed, record.network, record.scriptType, record.index).watchOnly(), fingerprint),
                name = record.name ?: auto(record.index),
                label = null,
                group = null,
            )
        }
        val solana = (0 until 3).map {
            PairingAccount(
                export = keyExport(SOLANA_MAINNET, fingerprint, accountSteps(44, 501, it), Curve.ED25519_SLIP10, SolanaAccount.publicKey(seed, it)),
                name = auto(it),
                label = null,
                group = null,
            )
        }
        val algorand = (0 until 2).map {
            PairingAccount(
                export = keyExport(algorandMainnet, fingerprint, accountSteps(44, 283, it, 0, 0), Curve.ED25519_BIP32, AlgorandAccount.publicKey(seed, it)),
                name = auto(it),
                label = null,
                group = null,
            )
        }

        val random = Random(20261118)
        fun evm(): String = checkNotNull(evmAddressOf("0x" + random.nextBytes(20).joinToString("") { "%02x".format(it) }))
        val addresses = listOf(
            ethereumAddress(0),
            ethereumAddress(1),
            ethereumAddress(2),
            evm(),
            evm(),
            evm(),
            BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0).receiveAddress(0),
            BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT, 0).receiveAddress(0),
            SolanaAccount.address(seed, 0),
            AlgorandAccount.addressOf(AlgorandAccount.publicKey(seed, 0)),
            evm(),
            evm(),
        )
        val contacts = listOf("Alice", "Bob", "Exchange deposit", "Cold storage", "Mum", "Dad", "Office", "Landlord", "Coffee shop", "Payroll", "Ledger", "Dentist")
        val book = addresses.mapIndexed { i, address ->
            AddressBookEntry(
                id = UUID(random.nextLong(), random.nextLong()).toString(),
                address = address,
                name = contacts[i],
                label = if (i % 3 == 0) "Family" else null,
            )
        }
        return PairingPayload(
            fingerprint = wallet.masterFingerprint.toLong() and 0xFFFFFFFFL,
            name = WALLET,
            autoNameBase = WALLET,
            accounts = ethereum + bitcoin + solana + algorand,
            addressBook = book,
        )
    }
}
