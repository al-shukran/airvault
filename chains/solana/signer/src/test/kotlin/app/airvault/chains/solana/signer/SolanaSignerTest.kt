package app.airvault.chains.solana.signer

import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import kotlin.test.assertEquals
import org.junit.Test

class SolanaSignerTest {
    private val chain = solanaManifest.networks.first().chainId

    @Test
    fun theAddressCodecGivesTheBase58AddressOfAKey() {
        // The all-zero key is the System Program's address, which is 32 ones in base58.
        val address = "11111111111111111111111111111111"
        assertEquals(address, SolanaSigner.addresses.fromPublicKey(chain, ByteArray(32), AddressKind.DEFAULT))
        assertEquals(AddressResult.Valid(address, AddressKind.DEFAULT), SolanaSigner.addresses.parse(chain, address))
    }
}
