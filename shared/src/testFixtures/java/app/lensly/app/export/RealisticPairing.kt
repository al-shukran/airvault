package app.lensly.app.export

import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.lensly.app.evmAddressOf
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

    /** The pairing of the vault: the Apps in their order, and the Address Book. */
    fun payload(): PairingPayload {
        val seed = Mnemonic.of(WORDS).use { it.deriveSeed("") }
        val wallet = HdWallet(seed)
        fun auto(index: Int) = autoAccountName(WALLET, index)
        fun ethereumAddress(account: Int): String = AccountAddresses.addressOfKey(AccountAddresses.addressXpub(wallet.accountXpub(account), 0))

        val ethereumNames = listOf("Main", null, "Savings", null, null)
        val ethereum = PairingApp(
            app = "ethereum",
            keys = ethereumNames.indices.map { PairingKey.ethereumAddress(it, ethereumAddress(it)) },
            accounts = ethereumNames.mapIndexed { i, name ->
                PairingAccount(key = i, name = name ?: auto(i), label = if (i == 0) "Cold" else null, group = if (i == 2) "Savings" else null)
            },
        )
        val stored = STORED.sortedWith(compareBy({ it.scriptType.purpose }, { it.network.coinType }, { it.index }))
        val bitcoin = PairingApp(
            app = "bitcoin",
            keys = stored.map { PairingKey.bitcoinAccount(BtcAccount.fromSeed(seed, it.network, it.scriptType, it.index).watchOnly()) },
            accounts = stored.mapIndexed { i, record -> PairingAccount(key = i, name = record.name ?: auto(record.index), label = null, group = null) },
        )
        val solana = PairingApp(
            app = "solana",
            keys = (0 until 3).map { PairingKey.solanaAccount(it, SolanaAccount.publicKey(seed, it)) },
            accounts = (0 until 3).map { PairingAccount(key = it, name = auto(it), label = null, group = null) },
        )
        val algorand = PairingApp(
            app = "algorand",
            keys = (0 until 2).map { PairingKey.algorandAccount(it, AlgorandAccount.publicKey(seed, it)) },
            accounts = (0 until 2).map { PairingAccount(key = it, name = auto(it), label = null, group = null) },
        )

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
            apps = listOf(ethereum, bitcoin, solana, algorand),
            addressBook = book,
        )
    }
}
