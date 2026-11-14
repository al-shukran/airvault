package app.lensly.app

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcPsbt
import app.lensly.core.btc.BtcRefusal
import app.lensly.core.btc.BtcRefusedException
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.InputVerificationPolicy.STRICT
import app.lensly.core.btc.ScriptType
import app.lensly.core.btc.SigningSourcePolicy
import app.lensly.core.ur.UrScanner
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import com.sparrowwallet.hummingbird.UREncoder
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Bitcoin accounts of a wallet: the first receive address against the published vectors, one account per Script Type, network
 * and index, a Single Key refused with its reason, and the watch-only export.
 */
class WalletStoreBitcoinTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private val cap = BulkCap.DEFAULT.accounts
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 7).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    private fun newWallet(): String = store.create(
        name = "Test",
        deviceGenerated = false,
        words = words,
        passphrase = null,
        nowMs = 1L,
    )

    @Test
    fun firstReceiveAddressesMatchThePublishedVectors() {
        // The BIP39 seed of the published "abandon abandon ... about" mnemonic, with no passphrase. The BIP84 and BIP86 vectors
        // are derived from it.
        val seed = hex(PUBLISHED_SEED)
        val native = bitcoinAccountRecords(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0..0).single()
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", native.firstReceiveAddress)
        val taproot = bitcoinAccountRecords(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT, 0..0).single()
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", taproot.firstReceiveAddress)
        // The testnet4 Nested Segwit row of the shared vectors file.
        val nested = bitcoinAccountRecords(seed, BtcNetwork.TESTNET4, ScriptType.NESTED_SEGWIT, 0..0).single()
        assertEquals("2Mww8dCYPUpKHofjgcXcBCEGmniw9CoaiD2", nested.firstReceiveAddress)
    }

    @Test
    fun aStoredAddressIsTheFirstReceiveAddressOfTheWalletsOwnAccount() {
        val id = newWallet()

        val added = assertNotNull(store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap))

        val expected = BtcAccount.fromSeed(Mnemonic.toSeed(words), BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0)
            .receiveAddress(0)
        assertEquals(expected, added.single().firstReceiveAddress)
        assertEquals(added, store.bitcoinAccounts(id))
    }

    @Test
    fun aWalletHasNoBitcoinAccountUntilOneIsAdded() {
        val id = newWallet()

        assertTrue(store.bitcoinAccounts(id).isEmpty())
        assertFailsWith<IllegalArgumentException> { store.bitcoinWatchOnlyParts(id) }
    }

    @Test
    fun aRunNumbersEachScriptTypeAndNetworkFromZeroAndHoldsEachKeyOnce() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 3, cap)
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 2, cap)
        store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 1, cap)
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 2, cap)

        val held = store.bitcoinAccounts(id)
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            held.filter { it.scriptType == ScriptType.NATIVE_SEGWIT && it.network == BtcNetwork.MAINNET }.map { it.index },
        )
        assertEquals(listOf(0, 1), held.filter { it.network == BtcNetwork.TESTNET4 }.map { it.index })
        assertEquals(listOf(0), held.filter { it.scriptType == ScriptType.TAPROOT }.map { it.index })
        assertEquals(held.size, held.map { it.key }.toSet().size)
    }

    @Test
    fun aRecordNeverHoldsOneKeyTwice() {
        val account = BitcoinAccountRecord(BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0, "bc1qexample")
        val twin = account.copy(firstReceiveAddress = "bc1qother")
        assertFailsWith<IllegalArgumentException> { WalletRecords.encodeBitcoinAccounts(listOf(account, twin)) }

        // A record that a writer put together with a repeated key is refused when it is read too.
        val repeated = WalletRecords.writeBytes { out ->
            out.writeByte(1)
            out.writeInt(2)
            repeat(2) {
                out.writeByte(BtcNetwork.MAINNET.coinType)
                out.writeByte(ScriptType.NATIVE_SEGWIT.purpose)
                out.writeInt(0)
                out.writeUTF("bc1qexample")
            }
        }
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeBitcoinAccounts(repeated) }
    }

    @Test
    fun aWalletKeepsAtMostOneHundredBitcoinAccounts() {
        val id = newWallet()
        val hundred = BulkCap.HUNDRED.accounts
        assertEquals(100, store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 100, hundred)?.size)

        assertFailsWith<IllegalArgumentException> {
            store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 1, hundred)
        }
        assertEquals(100, store.bitcoinAccounts(id).size)
    }

    @Test
    fun aSingleKeyWalletRefusesBitcoinAndSaysWhy() {
        val id = store.createSingleKey(name = "Key", privateKey = ByteArray(32) { 1 }, nowMs = 1L)

        val refusal = assertFailsWith<IllegalArgumentException> {
            store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        }
        assertEquals(SINGLE_KEY_NO_BITCOIN, refusal.message)
        assertTrue(store.bitcoinAccounts(id).isEmpty())
        assertFailsWith<IllegalArgumentException> { store.bitcoinWatchOnlyParts(id) }
    }

    @Test
    fun aRunAboveTheBulkCapIsRefusedAndSavesNothing() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> {
            store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 21, cap = 20)
        }
        assertTrue(store.bitcoinAccounts(id).isEmpty())
    }

    @Test
    fun theWatchOnlyExportCarriesEveryScriptTypeOfEachNetworkTheWalletHolds() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.TESTNET4, 1, cap)

        val scanner = UrScanner()
        store.bitcoinWatchOnlyParts(id).forEach { scanner.receive(it) }
        val ur = assertNotNull(scanner.result())
        val accounts = BtcWatchOnly.parseCryptoAccount(UREncoder.encode(ur))

        assertEquals(8, accounts.size)
        val allTypes = ScriptType.entries.toSet()
        assertEquals(allTypes, accounts.filter { it.network == BtcNetwork.MAINNET }.map { it.scriptType }.toSet())
        assertEquals(allTypes, accounts.filter { it.network == BtcNetwork.TESTNET4 }.map { it.scriptType }.toSet())
    }

    @Test
    fun theWatchOnlyExportCarriesEveryStoredAccountIndex() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 3, cap)

        val scanner = UrScanner()
        store.bitcoinWatchOnlyParts(id).forEach { scanner.receive(it) }
        val accounts = BtcWatchOnly.parseCryptoAccount(UREncoder.encode(assertNotNull(scanner.result())))

        // Indices 0, 1 and 2 on mainnet, with each of the four Script Types at every index.
        assertEquals(12, accounts.size)
        assertEquals(
            setOf(0, 1, 2),
            accounts.map { (it.path.path[2] - DeterministicWallet.hardenedKeyIndex).toInt() }.toSet(),
        )
    }

    @Test
    fun aReviewNamesTheStoredAccountTheRequestSpendsFromAndItsFee() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val reviewed = store.bitcoinReview(id, requestFrom(walletAccount(BtcNetwork.TESTNET4, 0)), STRICT, SigningSourcePolicy.ANY)

        assertEquals(BtcNetwork.TESTNET4, reviewed.account.network)
        assertEquals(ScriptType.NATIVE_SEGWIT, reviewed.account.scriptType)
        assertEquals(0, reviewed.account.index)
        assertEquals(100_000L, reviewed.review.inputs.single().amountSats)
        // The inputs hold 100,000 sat, the outputs 90,000 sat, so the fee is 10,000 sat.
        assertEquals(10_000L, reviewed.review.feeSats)
    }

    @Test
    fun aSignedRequestVerifiesAgainstItsPreviousOutputsAndIsReturnedAsFrames() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val account = walletAccount(BtcNetwork.TESTNET4, 0)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, paid = 30_000L, change = 60_000L))
        val reviewed = store.bitcoinReview(id, psbt, STRICT, SigningSourcePolicy.ANY)

        val frames = store.bitcoinSign(id, reviewed.account, psbt, STRICT, SigningSourcePolicy.ANY, unverifiedConfirmed = false)

        val scanner = UrScanner()
        frames.forEach { scanner.receive(it) }
        val signed = BtcPsbt.fromUr(assertNotNull(scanner.result()))
        val tx = BtcFixtures.finalize(signed, listOf(spend))
        assertTrue(BtcFixtures.verifies(tx, BtcFixtures.prevouts(listOf(spend))), "the interpreter rejects the signed request")
    }

    @Test
    fun aRequestSpendingFromAnAccountTheWalletDoesNotHoldIsRefused() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        val refused = assertFailsWith<BtcRefusedException> {
            store.bitcoinReview(id, requestFrom(walletAccount(BtcNetwork.MAINNET, 4)), STRICT, SigningSourcePolicy.ANY)
        }
        assertEquals(BtcRefusal.NoStoredAccount, refused.refusal)
    }

    @Test
    fun aRequestOnANetworkTheWalletHoldsNoAccountOnIsRefused() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        val refused = assertFailsWith<BtcRefusedException> {
            store.bitcoinReview(id, requestFrom(walletAccount(BtcNetwork.TESTNET4, 0)), STRICT, SigningSourcePolicy.ANY)
        }
        assertEquals(BtcRefusal.NetworkMismatch, refused.refusal)
    }

    @Test
    fun aRequestSpendingFromTwoAccountsOfTheWalletIsRefused() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 2, cap)
        val first = walletAccount(BtcNetwork.MAINNET, 0)
        val second = walletAccount(BtcNetwork.MAINNET, 1)
        val psbt = BtcFixtures.psbt(
            listOf(
                BtcFixtures.Spend(BtcFixtures.funding(first, 0, 0, 100_000L, salt = 1), first, 0, 0),
                BtcFixtures.Spend(BtcFixtures.funding(second, 0, 0, 100_000L, salt = 2), second, 0, 0),
            ),
            BtcFixtures.paying(first, paid = 30_000L, change = 60_000L),
        )
        val refused = assertFailsWith<BtcRefusedException> { store.bitcoinReview(id, psbt, STRICT, SigningSourcePolicy.ANY) }
        assertEquals(BtcRefusal.SeveralAccounts, refused.refusal)
    }

    @Test
    fun aWalletWithNoBitcoinAccountRefusesEveryRequest() {
        val id = newWallet()
        val refused = assertFailsWith<BtcRefusedException> {
            store.bitcoinReview(id, requestFrom(walletAccount(BtcNetwork.MAINNET, 0)), STRICT, SigningSourcePolicy.ANY)
        }
        assertEquals(BtcRefusal.NoStoredAccount, refused.refusal)
    }

    /** The account [index] of the wallet's own seed, on [network] and in Native Segwit. */
    private fun walletAccount(network: BtcNetwork, index: Int): BtcAccount =
        BtcAccount.fromSeed(Mnemonic.toSeed(words), network, ScriptType.NATIVE_SEGWIT, index)

    /** A request that spends the first output of [account] and pays a receive address and the change of the same account. */
    private fun requestFrom(account: BtcAccount): Psbt {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        return BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, paid = 30_000L, change = 60_000L))
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
        text.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }

    private companion object {
        /** The 64-byte BIP39 seed of the "abandon ... about" mnemonic, as app.lensly.core.btc's vectors file has it. */
        const val PUBLISHED_SEED =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    }
}
