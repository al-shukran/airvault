package app.lumio.pair

import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.algorand.wallet.AlgorandWallet
import app.airvault.core.model.Curve
import app.airvault.core.model.AccountId
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.accountSteps
import app.lensly.app.export.keyExport
import app.lensly.app.export.PairingPayload
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.chains.NativeBalances
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts
import com.sparrowwallet.hummingbird.UR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Algorand accounts of a Lensly pairing, as Lumio reads them. The seed is Mnemonic A, the 24-word vector of 23 `abandon` and `art` that
 * Lensly's Algorand tests use: account 0 shows the address the Ledger Algorand app shows for it.
 */
class PairAlgorandTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun lumioShowsTheAlgorandAddressesOfALensPairingAfterARoundTrip() {
        val pairing = roundTrip(payload(algorand = algorandKeys()))

        PairedWallets.save(session, pairing, nowMs = 100L)

        val listed = PairedWallets.list(session).single()
        assertEquals(2, listed.algorand.size)
        assertEquals(EXPECTED_ADDRESS_0, AlgorandAccount.addressOf(listed.algorand[0]))
        assertEquals(setOf(PairChain.EVM, PairChain.ALGORAND), listed.families)
    }

    @Test
    fun anAlgorandKeyOnAnotherPathIsRefused() {
        // The last level of an Algorand account key is 0, so a key at level 1 is outside its key scope.
        val wrongPath = (0 until 2).map { index ->
            PairingAccount(
                keyExport(algorandMainnet, PairFixtures.fingerprint(SEED), accountSteps(44, 283, index, 0, 1), Curve.ED25519_BIP32, AlgorandAccount.publicKey(SEED, index)),
                "Algorand $index",
                null,
                null,
            )
        }

        assertThrows(PairingRefusal::class.java) { roundTrip(payload(algorand = wrongPath)) }
    }

    @Test
    fun aPairingWithoutAlgorandHoldsNoAlgorandAccount() {
        PairedWallets.save(session, roundTrip(payload(algorand = emptyList())), nowMs = 100L)

        assertEquals(emptyList<ByteArray>(), PairedWallets.list(session).single().algorand)
    }

    @Test
    fun aPairingThatDropsTheAlgorandAccountsRemovesThem() {
        PairedWallets.save(session, roundTrip(payload(algorand = algorandKeys())), nowMs = 100L)
        PairedWallets.save(session, roundTrip(payload(algorand = emptyList())), nowMs = 200L)

        assertEquals(emptyList<ByteArray>(), PairedWallets.list(session).single().algorand)
    }

    @Test
    fun aPairedAlgorandAccountIsNamedByItsCaip10IdOnEachSide() {
        PairedWallets.save(session, roundTrip(payload(algorand = algorandKeys())), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        val addresses = wallet.algorand.map { AlgorandAccount.addressOf(it) }
        assertEquals(EXPECTED_ADDRESS_0, addresses[0])

        val mainnet = accountsOn(wallet, NetworkMode.MAINNET).filter { it.namespace == Networks.ALGORAND }
        val testnet = accountsOn(wallet, NetworkMode.TESTNET).filter { it.namespace == Networks.ALGORAND }

        assertEquals(addresses.map { AccountId.of(Networks.ALGO_MAINNET, it) }, mainnet.map { it.id })
        assertEquals(addresses.map { AccountId.of(Networks.ALGO_TESTNET, it) }, testnet.map { it.id })
        assertEquals(listOf(0, 1), mainnet.map { it.index })
    }

    @Test
    fun anAlgorandIdIsFoundInTheWalletByItsAddressOnEitherSide() {
        PairedWallets.save(session, roundTrip(payload(algorand = algorandKeys())), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()

        assertEquals(1, WalletAccounts.indexOf(wallet, AccountId.of(Networks.ALGO_TESTNET, AlgorandAccount.addressOf(wallet.algorand[1]))))
        assertEquals(0, WalletAccounts.indexOf(wallet, AccountId.of(Networks.ALGO_MAINNET, EXPECTED_ADDRESS_0)))
        assertNull(WalletAccounts.indexOf(wallet, AccountId.of(Networks.ALGO_MAINNET, "A".repeat(58))))
    }

    @Test
    fun theWalletAppAddressesAKeyAsLenslyDoes() {
        for (index in 0 until 2) {
            val key = AlgorandAccount.publicKey(SEED, index)

            assertEquals(AlgorandAccount.addressOf(key), AlgorandWallet.addresses.fromPublicKey(Networks.ALGO_MAINNET, key))
        }
    }

    /** The accounts of [wallet] on [mode]'s side, with no Ethereum or Bitcoin account and no balance read: only the Algorand and Solana keys. */
    private fun accountsOn(wallet: PairedWallet, mode: NetworkMode): List<WalletAccount> = WalletAccounts.of(
        wallet,
        mode,
        evmChains = emptyList(),
        evmCache = emptyList(),
        addressCount = 0,
        bitcoin = emptyList(),
        coins = NativeBalances.coinsOf(mode),
        nativeCache = emptyList(),
    )

    /** The pairing as Lumio reads it: the code is encoded as Lensly writes it, uncompressed, and checked by [PairImport]. */
    private fun roundTrip(payload: PairingPayload): PairingPayload =
        PairImport.of(UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload, null)))

    /** The Algorand accounts 0 and 1, as Lensly's pairing carries them: the public key of each, at its path. */
    private fun algorandKeys(): List<PairingAccount> = (0 until 2).map { index -> PairFixtures.algorandAccount(index, "Algorand $index", SEED) }

    private fun payload(algorand: List<PairingAccount>): PairingPayload =
        PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Main", seed = SEED)) + algorand, seed = SEED, name = "Demo", base = "Demo")

    private companion object {
        /** Mnemonic A's 64-byte BIP39 seed, with no passphrase. */
        val SEED: ByteArray = PairFixtures.seed

        /** The address of account 0 of Mnemonic A, which the Ledger Algorand app shows for it and LedgerEd25519Test asserts. */
        const val EXPECTED_ADDRESS_0 = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
    }
}
