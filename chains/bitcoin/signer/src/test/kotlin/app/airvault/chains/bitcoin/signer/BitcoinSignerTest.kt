package app.airvault.chains.bitcoin.signer

import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class BitcoinSignerTest {
    private val mainnet = bitcoinManifest.networks.first { !it.testnet }.chainId
    private val testnet4 = bitcoinManifest.networks.first { it.testnet }.chainId

    /** The compressed public key of private key 1, the generator point. */
    private val publicKey = "0279BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun theAddressCodecGivesTheNativeSegwitAddressOfAKey() {
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            BitcoinSigner.addresses.fromPublicKey(mainnet, publicKey, AddressKind.DEFAULT),
        )
    }

    @Test
    fun aTestnet4AddressIsParsedOnItsOwnNetwork() {
        val address = BitcoinSigner.addresses.fromPublicKey(testnet4, publicKey, AddressKind.DEFAULT)
        assertTrue(address.startsWith("tb1q"))
        assertEquals(AddressResult.Valid(address, AddressKind.DEFAULT), BitcoinSigner.addresses.parse(testnet4, address))
    }
}
