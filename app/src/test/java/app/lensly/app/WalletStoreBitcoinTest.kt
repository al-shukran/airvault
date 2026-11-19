package app.lensly.app

import app.lensly.app.export.LabelledPairingGolden
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.BtcRefusal
import app.airvault.chains.bitcoin.signer.BtcRefusedException
import app.lensly.core.btc.BtcUnsignedPsbt
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy.STRICT
import app.lensly.core.btc.ScriptType
import app.airvault.chains.bitcoin.signer.SigningSourcePolicy
import app.lensly.core.ur.UrScanner
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private val otherWords = List(23) { "zoo" } + "vote"
    /** The throwaway mnemonic of the Lumio request tests. */
    private val lumioWords = (
        "REDACTED-TEST-MNEMONIC REDACTED sketch " +
            "REDACTED REDACTED-TEST-MNEMONIC"
        ).split(" ")
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
        // The pairing still covers the wallet: its Bitcoin App is listed with no keys, so Lumio drops any Bitcoin account it held.
        assertTrue(pairingOf(store.pairingParts(id, null)).apps.single { it.app == "bitcoin" }.keys.isEmpty())
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
        assertFailsWith<IllegalArgumentException> { store.pairingParts(id, null) }
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
    fun thePairingCarriesOnlyTheStoredScriptTypeAndNetworkOfEachAccount() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.TESTNET4, 1, cap)

        val keys = bitcoinKeysOf(id)

        // The pairing carries the accounts the wallet stores, not every Script Type of each index: two stored accounts, two keys.
        assertEquals(2, keys.size)
        assertEquals(
            setOf(ScriptType.NATIVE_SEGWIT to BtcNetwork.MAINNET, ScriptType.TAPROOT to BtcNetwork.TESTNET4),
            keys.map { scriptTypeOf(it) to networkOf(it) }.toSet(),
        )
    }

    @Test
    fun thePairingCarriesEveryStoredAccountIndex() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 3, cap)

        // Indices 0, 1 and 2 on mainnet, one key each: the Script Type and the network are the stored ones only.
        val keys = bitcoinKeysOf(id)

        assertEquals(3, keys.size)
        assertEquals(setOf(0, 1, 2), keys.map { (it.path[2] - DeterministicWallet.hardenedKeyIndex).toInt() }.toSet())
    }

    @Test
    fun thePairingNamesEveryEthereumAccountAsLenslyShowsIt() {
        val id = store.create(name = "Savings", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
        store.addAccount(id)
        store.saveAccountProfile(id, 0, AccountProfile(name = "Cold", label = "vault", group = "Long term"))

        val pairing = pairingOf(store.pairingParts(id, null))

        assertEquals("Savings", pairing.name)
        val ethereum = pairing.apps.single { it.app == "ethereum" }
        val seed = Mnemonic.of(words).use { it.deriveSeed("") }
        assertEquals(2, ethereum.keys.size)
        // The Ethereum key of each account is its address, which the pairing carries as its 20 bytes.
        for (account in 0..1) {
            val address = AccountAddresses.addressOfKey(AccountAddresses.addressXpub(HdWallet(seed).accountXpub(account), 0))
            assertEquals(address.removePrefix("0x").lowercase(), ethereum.keys[account].material.joinToString("") { "%02x".format(it) })
        }
        assertEquals(
            listOf(
                PairingAccount(key = 0, name = "Cold", label = "vault", group = "Long term"),
                PairingAccount(key = 1, name = "Savings 1", label = null, group = null),
            ),
            ethereum.accounts,
        )
    }

    @Test
    fun aBitcoinProfileIsStoredWithItsAccountAndReadBack() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 2, cap)
        val profile = AccountProfile(name = "Cold", colour = ProfileColour.TEAL, icon = ProfileIcon.BANK, label = "vault", group = "Long term")

        store.saveBitcoinProfile(id, store.bitcoinAccounts(id)[1], profile)

        // A new store over the same session reads what was written, and the account that has no profile keeps its defaults.
        val read = WalletStore(session).bitcoinAccounts(id)
        assertEquals(listOf(AccountProfile(), profile), read.map { it.profile })
        // The record reads back to the same accounts and profiles.
        assertEquals(read, WalletRecords.decodeBitcoinAccounts(WalletRecords.encodeBitcoinAccounts(read)))
    }

    @Test
    fun renamingLabellingAndGroupingABitcoinAccountChangesOnlyTheFieldAsked() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 1, cap)
        val address = store.bitcoinAccounts(id).single().firstReceiveAddress
        fun current(): AccountProfile = store.bitcoinAccounts(id).single().profile
        fun save(profile: AccountProfile) = store.saveBitcoinProfile(id, store.bitcoinAccounts(id).single(), profile)

        save(current().copy(name = "Cold"))
        assertEquals(AccountProfile(name = "Cold"), current())
        save(current().copy(label = "vault"))
        assertEquals(AccountProfile(name = "Cold", label = "vault"), current())
        save(current().copy(group = "Long term"))
        assertEquals(AccountProfile(name = "Cold", label = "vault", group = "Long term"), current())
        // A rename keeps the label and the group.
        save(current().copy(name = "Hot"))
        assertEquals(AccountProfile(name = "Hot", label = "vault", group = "Long term"), current())
        // Clearing the label keeps the name and the group.
        save(current().copy(label = null))
        assertEquals(AccountProfile(name = "Hot", group = "Long term"), current())
        // A name that is only blank is refused, and a null name is the Auto-Name again.
        assertFailsWith<IllegalArgumentException> { save(current().copy(name = "  ")) }
        save(current().copy(name = null))
        assertEquals(AccountProfile(group = "Long term"), current())

        assertEquals(address, store.bitcoinAccounts(id).single().firstReceiveAddress)
    }

    @Test
    fun aProfileOfABitcoinAccountTheWalletDoesNotHoldIsRefused() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        val stranger = BitcoinAccountRecord(BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 7, "bc1qstranger")

        assertFailsWith<IllegalArgumentException> { store.saveBitcoinProfile(id, stranger, AccountProfile(name = "Ghost")) }
        assertTrue(store.bitcoinAccounts(id).all { it.profile.isEmpty })
    }

    @Test
    fun aSavedBitcoinProfileSurvivesMoreAccountsBeingAdded() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        val named = AccountProfile(name = "Cold", label = "vault")
        store.saveBitcoinProfile(id, store.bitcoinAccounts(id).single(), named)

        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 2, cap)

        val held = store.bitcoinAccounts(id)
        assertEquals(listOf(0, 1, 2), held.map { it.index })
        assertEquals(listOf(named, AccountProfile(), AccountProfile()), held.map { it.profile })
    }

    @Test
    fun aProfileThatDoesNotFitTheBitcoinRecordIsRefusedAndTheAccountKeepsItsOwn() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 100, BulkCap.HUNDRED.accounts)
        val long = AccountProfile(name = "N".repeat(32), label = "L".repeat(16), group = "G".repeat(16))

        // Each account takes a long profile in turn, until the record of the wallet has no room for one more.
        val refusal = (0 until 100).firstNotNullOfOrNull { position ->
            try {
                store.saveBitcoinProfile(id, store.bitcoinAccounts(id)[position], long)
                null
            } catch (e: IllegalArgumentException) {
                e
            }
        }

        val refused = assertNotNull(refusal, "a full record refuses one more profile")
        assertTrue(refused.message.orEmpty().contains("too much room"), refused.message)
        val saved = store.bitcoinAccounts(id).count { it.profile == long }
        assertTrue(saved in 10 until 100, "a record holds more than ten long profiles, and fewer than a hundred: $saved")
        assertEquals(100, store.bitcoinAccounts(id).size)
        assertEquals(AccountProfile(), store.bitcoinAccounts(id)[saved].profile)
    }

    @Test
    fun theBitcoinPairingCarriesTheNamesLabelsAndGroupsOfItsAccounts() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 2, cap)
        store.saveBitcoinProfile(id, store.bitcoinAccounts(id)[0], AccountProfile(name = "Cold", label = "vault", group = "Long term"))

        val bitcoin = pairingOf(store.pairingParts(id, null)).apps.single { it.app == "bitcoin" }

        // The named account carries its profile. An account with none carries its Auto-Name, as an Ethereum account does.
        assertEquals(Triple("Cold", "vault", "Long term"), bitcoinAccountOf(bitcoin, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 0).labelled())
        assertEquals(Triple("Test 1", null, null), bitcoinAccountOf(bitcoin, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1).labelled())
        assertEquals(2, bitcoin.keys.size)
        assertEquals(bitcoin.keys.size, bitcoin.accounts.size)
    }

    @Test
    fun theLabelledPairingIsTheFixtureLumioReads() {
        // Lumio reads these same frames in its own pairing test. A change here is a change to what Lumio is sent, so re-record the fixture
        // only when the pairing itself changes.
        val id = store.create(name = "Savings", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
        store.addAccount(id)
        store.saveAccountProfile(id, 0, AccountProfile(name = "Cold", label = "vault", group = "Long term"))
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 2, cap)
        store.saveBitcoinProfile(
            id,
            store.bitcoinAccounts(id)[0],
            AccountProfile(name = "Cold BTC", colour = ProfileColour.TEAL, label = "vault", group = "Long term"),
        )

        assertEquals(LabelledPairingGolden.FRAMES, store.pairingParts(id, null))
    }

    /** The pairing account of the Bitcoin key on [scriptType], [network] and account [index], in the pairing [app]. */
    private fun bitcoinAccountOf(app: PairingApp, scriptType: ScriptType, network: BtcNetwork, index: Int): PairingAccount {
        val key = app.keys.indexOfFirst { key ->
            scriptTypeOf(key) == scriptType && networkOf(key) == network && key.path[2] == DeterministicWallet.hardened(index.toLong())
        }
        assertTrue(key >= 0, "the pairing holds the key of $scriptType on $network at $index")
        return app.accounts.single { it.key == key }
    }

    private fun PairingAccount.labelled(): Triple<String?, String?, String?> = Triple(name, label, group)

    /** The Bitcoin keys of wallet [id], from the pairing that Lensly builds for it. */
    private fun bitcoinKeysOf(id: String): List<PairingKey> = pairingOf(store.pairingParts(id, null)).apps.single { it.app == "bitcoin" }.keys

    private fun networkOf(key: PairingKey): BtcNetwork =
        BtcNetwork.entries.single { it.coinType.toLong() == key.path[1] - DeterministicWallet.hardenedKeyIndex }

    private fun scriptTypeOf(key: PairingKey): ScriptType =
        ScriptType.entries.single { it.purpose.toLong() == key.path[0] - DeterministicWallet.hardenedKeyIndex }

    /** The pairing that [parts] carry, read as the online companion reads it. */
    private fun pairingOf(parts: List<String>): PairingPayload {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return PairingCodec.fromUr(assertNotNull(scanner.result()), null)
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

    /**
     * The request the Lumio companion builds from an account of the pairing, for each Script Type at index 0 of a vault that stores only a
     * Native Segwit account there. Only the stored Script Type is an account of the wallet: its request is reviewed and signed, and a request
     * from any other Script Type is refused, since the pairing carries the stored accounts alone.
     */
    @Test
    fun aRequestFromTheStoredScriptTypeIsReviewedAndAnotherScriptTypeIsRefused() {
        val id = store.create(name = "Lumio", deviceGenerated = false, words = lumioWords, passphrase = null, nowMs = 1L)
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val seed = Mnemonic.toSeed(lumioWords)
        for (type in ScriptType.entries) {
            val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, type, 0)
            val coin = BtcCoin(BtcFixtures.funding(account, 0, 0, 100_000L), 0, 0, 0)
            val built = BtcUnsignedPsbt.build(
                account.watchOnly(),
                listOf(coin),
                BtcFixtures.paying(account, paid = 30_000L, change = 60_000L),
            )
            // The request as the scanner reads it back from the QR code.
            val request = BtcPsbt.decode(BtcPsbt.encodeUr(built))

            if (type != ScriptType.NATIVE_SEGWIT) {
                // The wallet stores no account of this Script Type, so the request names none of the wallet's accounts.
                val refused = assertFailsWith<BtcRefusedException> { store.bitcoinReview(id, request, STRICT, SigningSourcePolicy.ANY) }
                assertEquals(BtcRefusal.NoStoredAccount, refused.refusal, type.name)
                continue
            }
            val reviewed = store.bitcoinReview(id, request, STRICT, SigningSourcePolicy.ANY)
            assertEquals(type, reviewed.account.scriptType, type.name)
            assertEquals(10_000L, reviewed.review.feeSats, type.name)

        }
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

    @Test
    fun anOutputPayingTheFirstReceiveAddressOfAnotherWalletIsNamedWithThatWallet() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val savings = store.create(name = "Savings", deviceGenerated = false, words = otherWords, passphrase = null, nowMs = 2L)
        store.addBitcoinAccounts(savings, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val other = BtcAccount.fromSeed(Mnemonic.toSeed(otherWords), BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
        val account = walletAccount(BtcNetwork.TESTNET4, 0)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            listOf(payingAddress(other.receiveAddress(0), 30_000L), payingAddress(account.changeAddress(0), 60_000L)),
        )

        val reviewed = store.bitcoinReview(id, psbt, STRICT, SigningSourcePolicy.ANY)

        val named = assertNotNull(reviewed.addressNotes.notesOf(other.receiveAddress(0))?.internal)
        assertEquals("Savings", named.walletName)
        assertEquals(autoAccountName(assertNotNull(store.meta(savings)).autoNameBase, 0), named.autoName)
    }

    @Test
    fun anOutputPayingTheSigningWalletsOwnAccountIsNamedWithoutAnotherWallet() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val savings = store.create(name = "Savings", deviceGenerated = false, words = otherWords, passphrase = null, nowMs = 2L)
        store.addBitcoinAccounts(savings, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val account = walletAccount(BtcNetwork.TESTNET4, 0)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            listOf(payingAddress(account.receiveAddress(0), 30_000L), payingAddress(account.changeAddress(0), 60_000L)),
        )

        val reviewed = store.bitcoinReview(id, psbt, STRICT, SigningSourcePolicy.ANY)

        val own = assertNotNull(reviewed.addressNotes.notesOf(account.receiveAddress(0))?.internal)
        assertNull(own.walletName)
    }

    @Test
    fun aChangeOutputToTheSigningWalletsOwnAccountIsNamedWithTheAccount() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        val account = walletAccount(BtcNetwork.TESTNET4, 0)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            listOf(payingAddress(account.receiveAddress(0), 30_000L), payingAddress(account.changeAddress(0), 60_000L)),
        )

        val reviewed = store.bitcoinReview(id, psbt, STRICT, SigningSourcePolicy.ANY)

        val change = assertNotNull(reviewed.addressNotes.notesOf(account.changeAddress(0))?.internal)
        assertNull(change.walletName)
        assertEquals(autoAccountName("Test", 0), change.autoName)
    }

    @Test
    fun anOutputToAnAddressNoAccountOfTheVaultHoldsIsNotNamed() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap)
        // A seed no wallet of the vault holds: the same words with another passphrase.
        val outsider = BtcAccount.fromSeed(Mnemonic.toSeed(otherWords, passphrase = "outside"), BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
        val account = walletAccount(BtcNetwork.TESTNET4, 0)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            listOf(payingAddress(outsider.receiveAddress(0), 30_000L), payingAddress(account.changeAddress(0), 60_000L)),
        )

        val reviewed = store.bitcoinReview(id, psbt, STRICT, SigningSourcePolicy.ANY)

        assertNull(reviewed.addressNotes.notesOf(outsider.receiveAddress(0)))
    }

    /** An output of [amount] sat that pays [address] on Testnet4, the way a request names the address it pays. */
    private fun payingAddress(address: String, amount: Long): TxOut {
        val script = Bitcoin.addressToPublicKeyScript(BtcNetwork.TESTNET4.chain.chainHash, address).right
        return TxOut(Satoshi(amount), ByteVector(Script.write(checkNotNull(script))))
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
