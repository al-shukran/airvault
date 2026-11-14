package app.lensly.core.btc

import app.lensly.core.wallet.HdWallet
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.DeterministicWallet
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class BtcAccountTest {
    private val seed = BitcoinVectors.seed()

    @Test
    fun addressesMatchTheReferenceForEveryScriptTypeAndNetwork() {
        for (network in BtcNetwork.entries) {
            for (type in ScriptType.entries) {
                val account = BtcAccount.fromSeed(seed, network, type)
                val key = "${BitcoinVectors.networkName(network)}.${BitcoinVectors.typeName(type)}"
                assertEquals(BitcoinVectors["$key.receive.0"], account.receiveAddress(0), "$key receive 0")
                assertEquals(BitcoinVectors["$key.receive.1"], account.receiveAddress(1), "$key receive 1")
                assertEquals(BitcoinVectors["$key.change.0"], account.changeAddress(0), "$key change 0")
            }
        }
    }

    @Test
    fun publishedBip84VectorGivesThePublicKeyAndAddress() {
        val account = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        assertEquals(BitcoinVectors["mainnet.native.pub.receive.0"], account.publicKey(0, 0).toHex())
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", account.receiveAddress(0))
    }

    @Test
    fun publishedBip86VectorGivesTheTaprootInternalKey() {
        val account = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT)
        assertEquals(BitcoinVectors["mainnet.taproot.internal.receive.0"], account.publicKey(0, 0).xOnly().value.toHex())
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", account.receiveAddress(0))
    }

    @Test
    fun publishedBip49TestnetVectorGivesTheKeyAndAddress() {
        val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NESTED_SEGWIT)
        assertEquals(BitcoinVectors["testnet4.nested.pub.receive.0"], account.publicKey(0, 0).toHex())
        assertEquals("2Mww8dCYPUpKHofjgcXcBCEGmniw9CoaiD2", account.receiveAddress(0))
    }

    @Test
    fun accountXpubsMatchThePublishedKeysInTheirSlip132Form() {
        val native = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        assertEquals(BitcoinVectors["mainnet.native.zpub"], native.xpub.encode(DeterministicWallet.zpub))
        val nested = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NESTED_SEGWIT)
        assertEquals(BitcoinVectors["testnet4.nested.upub"], nested.xpub.encode(DeterministicWallet.upub))
        val taproot = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT)
        assertEquals(BitcoinVectors["mainnet.taproot.xpub"], taproot.xpub.encode(DeterministicWallet.xpub))
    }

    @Test
    fun accountPathsCarryThePurposeAndTheCoinType() {
        assertEquals("m/44'/0'/0'", ScriptType.LEGACY.accountPath(BtcNetwork.MAINNET, 0).asString('\''))
        assertEquals("m/84'/1'/2'", ScriptType.NATIVE_SEGWIT.accountPath(BtcNetwork.TESTNET4, 2).asString('\''))
        assertEquals("m/86'/1'/0'", BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT).path.asString('\''))
    }

    @Test
    fun masterFingerprintIsTheEthereumWalletFingerprintOfTheSameSeed() {
        val account = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.LEGACY)
        val ethereumFingerprint = HdWallet(seed).masterFingerprint.toLong() and 0xFFFFFFFFL
        assertEquals(ethereumFingerprint, account.masterFingerprint)
        assertEquals(BitcoinVectors["root.fingerprint"].toLong(16), account.masterFingerprint)
    }

    @Test
    fun rootFromSeedIsTheBip32MasterOfBitcoinKmp() {
        assertEquals(DeterministicWallet.generate(seed), rootFromSeed(seed))
    }

    @Test
    fun scriptPubKeysDecodeToTheirAddresses() {
        for (network in BtcNetwork.entries) {
            for (type in ScriptType.entries) {
                val account = BtcAccount.fromSeed(seed, network, type)
                val decoded = Bitcoin.addressFromPublicKeyScript(network.chain.chainHash, account.scriptPubKey(0, 3)).right
                assertEquals(account.receiveAddress(3), decoded, "${network.name} ${type.name}")
            }
        }
    }

    @Test
    fun testnet4AddressesUseTheTestnetPrefixes() {
        val legacy = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.LEGACY).receiveAddress(0)
        val nested = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NESTED_SEGWIT).receiveAddress(0)
        val native = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT).receiveAddress(0)
        val taproot = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT).receiveAddress(0)
        assertTrue(legacy.first() == 'm' || legacy.first() == 'n', legacy)
        assertTrue(nested.startsWith("2"), nested)
        assertTrue(native.startsWith("tb1q"), native)
        assertTrue(taproot.startsWith("tb1p"), taproot)
    }

    @Test
    fun addressIndexesAndBranchesAreChecked() {
        val account = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        assertFailsWith<IllegalArgumentException> { account.receiveAddress(-1) }
        assertFailsWith<IllegalArgumentException> { account.publicKey(2, 0) }
    }

    @Test
    fun seedLengthAndAccountIndexAreChecked() {
        assertFailsWith<IllegalArgumentException> { BtcAccount.fromSeed(ByteArray(15), BtcNetwork.MAINNET, ScriptType.LEGACY) }
        assertFailsWith<IllegalArgumentException> {
            BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.LEGACY, accountIndex = -1)
        }
    }

}
