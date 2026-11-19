package app.lumio.pair

import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import com.sparrowwallet.hummingbird.UR
import org.junit.Assert.assertEquals
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
        val wrongPath = algorandKeys().map { key -> key.copy(path = key.path.dropLast(1) + (key.path.last() + 1)) }

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

    /** The pairing as Lumio reads it: the code is encoded as Lensly writes it, uncompressed, and checked by [PairImport]. */
    private fun roundTrip(payload: PairingPayload): PairingPayload =
        PairImport.of(UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload, null)))

    /** The Algorand keys of accounts 0 and 1, as Lensly's pairing carries them: the public key and the chain code of each. */
    private fun algorandKeys(): List<PairingKey> = (0 until 2).map { index ->
        PairingKey.algorandAccount(index, AlgorandAccount.publicKey(SEED, index))
    }

    private fun payload(algorand: List<PairingKey>): PairingPayload {
        val ethereum = PairingApp(
            app = PairImport.ETHEREUM,
            keys = listOf(PairingKey.ethereumAddress(0, PairFixtures.address(SEED, 0))),
            accounts = listOf(PairingAccount(key = 0, name = "Main", label = null, group = null)),
        )
        val algorandApp = PairingApp(
            app = PairImport.ALGORAND,
            keys = algorand,
            accounts = algorand.indices.map { index ->
                PairingAccount(key = index, name = "Algorand $index", label = null, group = null)
            },
        )
        return PairingPayload(
            fingerprint = 0x5436D724L,
            name = "Demo",
            autoNameBase = "Demo",
            apps = listOf(ethereum, algorandApp),
        )
    }

    private companion object {
        /** Mnemonic A's 64-byte BIP39 seed, with no passphrase. */
        val SEED: ByteArray = PairFixtures.seed

        /** The address of account 0 of Mnemonic A, which the Ledger Algorand app shows for it and LedgerEd25519Test asserts. */
        const val EXPECTED_ADDRESS_0 = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
    }
}
