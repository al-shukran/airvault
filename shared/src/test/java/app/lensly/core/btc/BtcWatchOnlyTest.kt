package app.lensly.core.btc

import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.DeterministicWallet
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class BtcWatchOnlyTest {
    private val seed = BitcoinVectors.seed()

    @Test
    fun cryptoAccountPartsDecodeToTheSameCryptoAccount() {
        val accounts = BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET)
        val parts = BtcWatchOnly.cryptoAccountParts(accounts)
        // Four outputs carry four extended public keys, which do not fit one QR code. The parts are the uncoded fragments of the
        // animated code, so the first frame names the count of them.
        assertTrue(parts.size > 1, "parts: ${parts.size}")
        assertTrue(parts.first().startsWith("UR:CRYPTO-ACCOUNT/1-${parts.size}/"), parts.first().take(40))

        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        val decoded = assertNotNull(scanner.result())
        val single = BtcWatchOnly.cryptoAccount(accounts)
        assertContentEquals(URDecoder.decode(single).cborBytes, decoded.cborBytes)
        assertEquals(accounts, BtcWatchOnly.parseCryptoAccount(single))
    }

    @Test
    fun descriptorsMatchTheReferenceForEveryScriptTypeAndNetwork() {
        for (network in BtcNetwork.entries) {
            for (type in ScriptType.entries) {
                val account = BtcAccount.fromSeed(seed, network, type).watchOnly()
                val key = "${BitcoinVectors.networkName(network)}.${BitcoinVectors.typeName(type)}"
                val expected = BitcoinVectors["$key.descriptor"]
                assertEquals(expected, account.descriptor, key)
            }
        }
    }

    @Test
    fun descriptorChecksumMatchesTheBip380Vector() {
        assertEquals(BitcoinVectors["bip380.checksum.output"], Descriptor.checksum(BitcoinVectors["bip380.checksum.input"]))
    }

    @Test
    fun cryptoAccountCarriesOneOutputPerScriptTypeAndRoundTrips() {
        for (network in BtcNetwork.entries) {
            val accounts = BtcWatchOnly.accounts(seed, network)
            assertEquals(ScriptType.entries.toSet(), accounts.map { it.scriptType }.toSet())
            val ur = BtcWatchOnly.cryptoAccount(accounts)
            assertTrue(ur.startsWith("ur:crypto-account/"), ur.take(40))
            assertEquals(accounts, BtcWatchOnly.parseCryptoAccount(ur), network.name)
        }
    }

    @Test
    fun parsedAccountsGiveTheSameDescriptors() {
        val accounts = BtcWatchOnly.accounts(seed, BtcNetwork.TESTNET4, accountIndex = 1)
        val parsed = BtcWatchOnly.parseCryptoAccount(BtcWatchOnly.cryptoAccount(accounts))
        assertEquals(accounts.map { it.descriptor }, parsed.map { it.descriptor })
        assertEquals(List(4) { DeterministicWallet.hardened(1L) }, parsed.map { it.path.path[2] })
    }

    @Test
    fun aUrOfAnotherTypeIsNotACryptoAccount() {
        val otherType = UREncoder.encode(CryptoPSBT(byteArrayOf(1, 2, 3)).toUR())
        assertFailsWith<IllegalArgumentException> { BtcWatchOnly.parseCryptoAccount(otherType) }
        assertFailsWith<IllegalArgumentException> { BtcWatchOnly.parseCryptoAccount("ur:crypto-account/nonsense") }
    }

    @Test
    fun cryptoAccountRefusesAccountsOfTwoSeeds() {
        val other = ByteArray(16) { 7 }
        val mixed = BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET) + BtcWatchOnly.accounts(other, BtcNetwork.MAINNET)
        assertFailsWith<IllegalArgumentException> { BtcWatchOnly.cryptoAccount(mixed) }
    }
}
