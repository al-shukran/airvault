package app.airvault.chains.evm.signer

import app.airvault.chains.evm.evmManifest
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import kotlin.test.assertEquals
import org.junit.Test

class EvmSignerTest {
    private val chain = evmManifest.networks.first().chainId

    @Test
    fun theAddressCodecGivesTheChecksumAddressOfAnUncompressedKey() {
        // The uncompressed public key of private key 1. Its Ethereum address is the one below, in EIP-55 form.
        val key = ("04" + "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798" +
            "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val address = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
        assertEquals(address, EvmSigner.addresses.fromPublicKey(chain, key, AddressKind.DEFAULT))
        assertEquals(AddressResult.Valid(address, AddressKind.DEFAULT), EvmSigner.addresses.parse(chain, address))
    }
}
