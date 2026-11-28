package app.lumio.pair

import app.lensly.app.autoAccountName
import app.lensly.app.export.PairingCodec
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcRow
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.wallets.WalletAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wallet that Lensly names account by account, paired into Lumio: the list of accounts that the Wallet screen renders shows the name of
 * every account, on every chain, and not only the Ethereum ones.
 */
class PairedNamesInListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy { VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() }) }

    @Test
    fun theListShowsTheNameOfEverySolanaAlgorandAndBitcoinAccountAfterPairing() {
        val bitcoin = BtcAccount.fromSeed(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT).watchOnly()
        val sent = PairFixtures.payloadOf(
            listOf(
                PairFixtures.ethereumAccount(0, "Rent"),
                PairFixtures.bitcoinAccount(bitcoin, "Cold Storage"),
                PairFixtures.solanaAccount(0, "Trading"),
                PairFixtures.solanaAccount(1, "Memecoins"),
                PairFixtures.algorandAccount(0, "Savings"),
            ),
        )

        PairedWallets.save(session, pairingOf(PairingCodec.urParts(sent, brotli = null)), nowMs = 1L)
        val wallet = PairedWallets.list(session).single()
        val accounts = WalletAccounts.of(
            wallet,
            NetworkMode.MAINNET,
            evmChains = listOf(EVM_MAINNET),
            evmCache = emptyList(),
            addressCount = 1,
            bitcoin = listOf(BtcRow(BtcAccountRef(wallet.id, bitcoin), null)),
            coins = emptyList(),
            nativeCache = emptyList(),
        )

        assertEquals(
            listOf(
                Networks.BIP122 to "Cold Storage",
                Networks.EIP155 to "Rent",
                Networks.SOLANA to "Trading",
                Networks.SOLANA to "Memecoins",
                Networks.ALGORAND to "Savings",
            ),
            accounts.map { it.namespace to it.name },
        )
    }

    @Test
    fun aRepairRenamesTheSolanaAccountsAndDropsTheNamesOfTheAccountsLenslyRemoved() {
        PairedWallets.save(session, PairFixtures.payloadOf(listOf(PairFixtures.solanaAccount(0, "Trading"), PairFixtures.solanaAccount(1, "Memecoins"))), nowMs = 1L)
        PairedWallets.save(session, PairFixtures.payloadOf(listOf(PairFixtures.solanaAccount(0, "Payroll"))), nowMs = 2L)

        assertEquals(listOf<String?>("Payroll"), PairedWallets.list(session).single().solanaNames)
    }

    @Test
    fun namesThatTakeMoreRoomThanTheWalletRecordHoldsRefuseTheCodeAtPairing() {
        val long = "N".repeat(PairingCodec.MAX_NAME_BYTES)
        val sent = PairFixtures.payloadOf((0 until 60).map { PairFixtures.solanaAccount(it, long) } + (0 until 60).map { PairFixtures.algorandAccount(it, long) })

        assertTrue(scanToEnd(PairingCodec.urParts(sent, brotli = null)) is ScanStep.Refused)
    }

    @Test
    fun aHundredAutoNamedSolanaAndAlgorandAccountsUnderALongBasePairAndShowTheirAutoNames() {
        val base = "My Long Wallet Name Of 22"
        val sent = PairFixtures.payloadOf(
            (0 until 100).map { PairFixtures.solanaAccount(it, if (it == 7) "Payroll" else autoAccountName(base, it)) } +
                (0 until 100).map { PairFixtures.algorandAccount(it, autoAccountName(base, it)) },
            name = base,
            base = base,
        )

        PairedWallets.save(session, pairingOf(PairingCodec.urParts(sent, brotli = null)), nowMs = 1L)
        val wallet = PairedWallets.list(session).single()
        val accounts = WalletAccounts.of(
            wallet, NetworkMode.MAINNET, evmChains = listOf(EVM_MAINNET), evmCache = emptyList(), addressCount = 1,
            bitcoin = emptyList(), coins = emptyList(), nativeCache = emptyList(),
        )

        assertEquals(
            (0 until 100).map { if (it == 7) "Payroll" else autoAccountName(base, it) } + (0 until 100).map { autoAccountName(base, it) },
            accounts.map { it.name },
        )
        assertEquals(listOf<String?>("Payroll"), wallet.solanaNames.filterNotNull())
    }

    private companion object {
        val EVM_MAINNET = app.lumio.evm.EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    }
}
