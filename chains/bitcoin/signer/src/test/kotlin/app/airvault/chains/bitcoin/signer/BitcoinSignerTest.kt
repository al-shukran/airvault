package app.airvault.chains.bitcoin.signer

import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.signer.Path
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BitcoinSignerTest {
    private val signer = BitcoinSigner()
    private val mainnet = bitcoinManifest.networks.first { !it.testnet }.chainId
    private val testnet4 = bitcoinManifest.networks.first { it.testnet }.chainId

    /** The compressed public key of private key 1, the generator point. */
    private val publicKey = "0279BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun theAddressCodecGivesTheNativeSegwitAddressOfAKey() {
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            signer.addresses.fromPublicKey(mainnet, publicKey, AddressKind.DEFAULT),
        )
    }

    @Test
    fun aTestnet4AddressIsParsedOnItsOwnNetwork() {
        val address = signer.addresses.fromPublicKey(testnet4, publicKey, AddressKind.DEFAULT)
        assertTrue(address.startsWith("tb1q"))
        assertEquals(AddressResult.Valid(address, AddressKind.DEFAULT), signer.addresses.parse(testnet4, address))
    }

    /** The path m/84'/1'/0' of the first native segwit account on testnet4, which a wallet stores for it. */
    private val storedAccount = Path(listOf(PathStep(84, true), PathStep(1, true), PathStep(0, true)))

    @Test
    fun aRequestNamesTheStoredAccountItSpendsFromByItsAccountLevels() {
        val spendsFrom = SignerRef(0x1a2b3c4d, storedAccount.steps + listOf(PathStep(0, false), PathStep(5, false)))
        val otherAccount = Path(listOf(PathStep(84, true), PathStep(1, true), PathStep(1, true)))
        val otherPurpose = Path(listOf(PathStep(44, true), PathStep(1, true), PathStep(0, true)))

        assertTrue(signer.namesStoredAccount(spendsFrom, listOf(storedAccount)))
        assertFalse(signer.namesStoredAccount(spendsFrom, listOf(otherAccount)))
        assertFalse(signer.namesStoredAccount(spendsFrom, listOf(otherPurpose)))
        assertFalse(signer.namesStoredAccount(spendsFrom, emptyList()))
    }

    @Test
    fun anAccountLevelThatIsNotHardenedNamesNoStoredAccount() {
        val unhardened = SignerRef(0x1a2b3c4d, listOf(PathStep(84, true), PathStep(1, true), PathStep(0, false), PathStep(0, false)))

        assertFalse(signer.namesStoredAccount(unhardened, listOf(storedAccount)))
    }
}
