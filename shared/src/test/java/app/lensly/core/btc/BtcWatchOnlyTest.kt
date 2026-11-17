package app.lensly.core.btc

import fr.acinq.bitcoin.Descriptor
import kotlin.test.assertEquals
import org.junit.Test

class BtcWatchOnlyTest {
    private val seed = BitcoinVectors.seed()


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
    fun watchOnlyChangeAddressesMatchTheReferenceAndTheFullAccount() {
        for (network in BtcNetwork.entries) {
            for (type in ScriptType.entries) {
                val key = "${BitcoinVectors.networkName(network)}.${BitcoinVectors.typeName(type)}"
                val watchOnly = BtcAccount.fromSeed(seed, network, type).watchOnly()
                assertEquals(BitcoinVectors["$key.change.0"], watchOnly.changeAddress(0), "$key change 0")
                for (index in 0..2) {
                    assertEquals(
                        BtcAccount.fromSeed(seed, network, type).changeAddress(index),
                        watchOnly.changeAddress(index),
                        "$key change $index",
                    )
                }
            }
        }
    }

    @Test
    fun descriptorChecksumMatchesTheBip380Vector() {
        assertEquals(BitcoinVectors["bip380.checksum.output"], Descriptor.checksum(BitcoinVectors["bip380.checksum.input"]))
    }




}
