package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lumio.network.NetworkMode
import app.lumio.pair.PairRecords
import app.lumio.pair.PairedWallet
import app.lumio.data.net.Networks
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts
import fr.acinq.bitcoin.DeterministicWallet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lensly and Lumio show one Bitcoin address for each account and network. The seed is the BIP39 seed of the published "abandon ... about" mnemonic, and the two accounts
 * are numbers 0 and 4 of native segwit, so one wallet holds two accounts of the same network. For each network, the address that Lensly records for an
 * account, its first receive address m/.../0/0, must be the address of its list row, of the account page that the row opens, and the first address that
 * the balance lookup asks the server for. Lumio reads each account from the vault record that pairing writes, so it holds only the extended public key.
 */
class BtcAddressAgreementTest {
    // The BIP39 seed of the published "abandon ... about" mnemonic, with no passphrase. WalletStoreBitcoinTest pins its vectors.
    private val seed = hex(
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
    )
    private val accountNumbers = listOf(0, 4)

    private val history = EsploraAddressStats(
        chain = EsploraSide(fundedCount = 1L, fundedSum = 10_000L, spentCount = 0L, spentSum = 0L, txCount = 1L),
        mempool = EsploraSide(fundedCount = 0L, fundedSum = 0L, spentCount = 0L, spentSum = 0L, txCount = 0L),
    )
    private val noHistory = EsploraAddressStats(
        chain = EsploraSide(fundedCount = 0L, fundedSum = 0L, spentCount = 0L, spentSum = 0L, txCount = 0L),
        mempool = EsploraSide(fundedCount = 0L, fundedSum = 0L, spentCount = 0L, spentSum = 0L, txCount = 0L),
    )

    @Test
    fun mainnetAccountsShowLenslysAddressInTheListTheAccountPageAndTheScan() {
        checkAgreement(BtcNetwork.MAINNET, NetworkMode.MAINNET)
    }

    @Test
    fun testnetAccountsShowLenslysAddressInTheListTheAccountPageAndTheScan() {
        checkAgreement(BtcNetwork.TESTNET4, NetworkMode.TESTNET)
    }

    private fun checkAgreement(network: BtcNetwork, mode: NetworkMode) {
        // Lensly's own accounts: each one's first receive address, and its receive address 4, which the balance lookup reaches.
        val lensly = accountNumbers.map { BtcAccount.fromSeed(seed, network, ScriptType.NATIVE_SEGWIT, it) }
        val firstReceive = lensly.map { it.receiveAddress(0) }
        val fourthReceive = lensly.map { it.receiveAddress(4) }
        val prefix = if (network == BtcNetwork.MAINNET) "bc1q" else "tb1q"
        assertTrue(firstReceive.all { it.startsWith(prefix) })
        if (network == BtcNetwork.MAINNET) {
            // The published BIP84 vector of this seed, which WalletStoreBitcoinTest pins as Lensly's first receive address.
            assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", firstReceive.first())
        }

        // Lumio's accounts: the vault record that pairing writes, read back with only the extended public keys.
        val paired = PairRecords.decodeBitcoin(
            PairRecords.encodeBitcoin(lensly.map { it.watchOnly() }),
            fingerprint = lensly.first().masterFingerprint,
        )
        // The account paths: m/84'/coin'/N', with coin 0' on mainnet and 1' on testnet4, the BIP44 coin type that test networks use.
        for (position in accountNumbers.indices) {
            val path = listOf(84L, network.coinType.toLong(), accountNumbers[position].toLong()).map { DeterministicWallet.hardened(it) }
            assertEquals(path, paired[position].path.path)
        }
        val wallet = PairedWallet(
            id = "wallet-1",
            name = "Demo",
            fingerprint = lensly.first().masterFingerprint,
            createdAtMs = 1L,
            bitcoin = paired,
        )
        val refs = wallet.bitcoin.map { BtcAccountRef(wallet.id, it) }

        // The list before any scan, and after a scan that found receive addresses 0 to 4 in use, so the next unused one is 5.
        val unscanned = refs.map { BtcRow(it, null) }
        val scanned = refs.zip(paired).map { (ref, account) -> BtcRow(ref, scanSnapshot(ref, account)) }
        for (rows in listOf(unscanned, scanned)) {
            val list = bitcoinRowsOf(wallet, mode, rows)
            assertEquals(firstReceive, list.map { it.address })
            assertEquals(listOf(0, 1), list.map { it.index })
            // The account page opens the row with the same index, and shows the same address.
            for (position in accountNumbers.indices) {
                val page = list.first { it.index == position }
                assertEquals(firstReceive[position], page.address)
            }
        }

        // The balance lookup walks each account's receive chain from index 0. Its first request is Lensly's first receive address, and index 4 is in use.
        for (position in accountNumbers.indices) {
            val asked = mutableListOf<String>()
            val walk = runBlocking {
                scanChain(
                    addressAt = paired[position]::receiveAddress,
                    statsOf = { address ->
                        asked += address
                        if (address == fourthReceive[position]) history else noHistory
                    },
                )
            }
            assertEquals(firstReceive[position], asked.first())
            assertEquals(fourthReceive[position], asked[4])
            assertEquals(listOf(4), walk.used.map { it.index })
            assertEquals(listOf(fourthReceive[position]), walk.used.map { it.address })
        }
    }

    /** The Bitcoin accounts of [wallet] on [mode]'s side, as the Wallets list builds them, with no Ethereum, Solana or Algorand account. */
    private fun bitcoinRowsOf(wallet: PairedWallet, mode: NetworkMode, rows: List<BtcRow>): List<WalletAccount> =
        WalletAccounts.of(
            wallet,
            mode,
            evmChains = emptyList(),
            evmCache = emptyList(),
            addressCount = 0,
            bitcoin = rows,
            coins = emptyList(),
            nativeCache = emptyList(),
        ).filter { it.namespace == Networks.BIP122 }

    /** A scan that found receive addresses 0 to 4 in use: its identity is the first receive address, and its next unused one is index 5. */
    private fun scanSnapshot(ref: BtcAccountRef, account: BtcWatchOnlyAccount): BtcSnapshot = BtcSnapshot(
        key = ref.key,
        identity = account.receiveAddress(0),
        scannedAtMs = 1L,
        tipHeight = 1L,
        confirmedSats = 0L,
        unconfirmedSats = 0L,
        nextReceiveIndex = 5,
        nextReceiveAddress = account.receiveAddress(5),
        utxos = emptyList(),
        transactions = emptyList(),
    )

    /** The bytes of a hex string, two digits to a byte. */
    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
