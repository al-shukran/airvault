package app.airvault.chains.evm.wallet

import app.airvault.core.model.AccountId
import app.airvault.core.model.AddressKind
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainRegistry
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EvmWalletTest {
    private val mainnet = ChainId("eip155:1")
    private val monad = ChainId("eip155:10143")
    private val sepolia = ChainId("eip155:11155111")

    // The secp256k1 generator, the public key of private key 1, uncompressed. Its Ethereum address is the one MultiChainAddressBookTest checks.
    private val generator = hex(
        "04" + "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798" + "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8",
    )
    private val generatorAddress = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"

    @Test
    fun theTestnetSideOpensOnMonadAndTheMainnetSideOnEthereum() {
        assertEquals(monad, EvmWallet.primaryNetwork(testnet = true))
        assertEquals(mainnet, EvmWallet.primaryNetwork(testnet = false))
    }

    @Test
    fun aNewVaultReadsEthereumMonadAndSepolia() {
        assertEquals(listOf(mainnet, monad, sepolia), EvmWallet.networksEnabledByDefault())
    }

    @Test
    fun accountFromNamesTheAddressOfTheKeyOnTheNetworkItNames() {
        val export = export(chain = monad, index = 0, key = generator)

        val account = EvmWallet.accountFrom(export).single()

        assertEquals(AccountId("eip155:10143:$generatorAddress"), account.id)
        assertEquals(export.signer, account.signer)
        assertEquals(generator.toList(), account.publicKey.bytes.toList())
        assertEquals(AddressKind.DEFAULT, account.kind)
    }

    @Test
    fun theSameKeyHasTheSameAddressOnEveryEvmNetwork() {
        for (chain in listOf(mainnet, monad, sepolia)) {
            assertEquals(generatorAddress, EvmWallet.accountFrom(export(chain, index = 2, key = generator)).single().id.address)
        }
    }

    @Test
    fun pathOfAccountNIsTheManifestPathAndAccountFromAcceptsIt() {
        assertEquals(
            listOf(PathStep(44, true), PathStep(60, true), PathStep(4, true), PathStep(0, false), PathStep(0, false)),
            EvmWallet.pathOf(4),
        )
        for (index in 0..2) {
            assertEquals(1, EvmWallet.accountFrom(export(mainnet, index, generator)).size)
        }
    }

    @Test
    fun refusesAnExportThatIsNotAnEthereumAccountKey() {
        assertFailsWith<IllegalArgumentException> { EvmWallet.accountFrom(export(ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k"), 0, generator)) }
        assertFailsWith<IllegalArgumentException> { EvmWallet.accountFrom(export(mainnet, 0, generator, curve = Curve.ED25519_BIP32)) }
        assertFailsWith<IllegalArgumentException> { EvmWallet.accountFrom(export(mainnet, 0, generator, path = "m/44'/283'/0'/0/0")) }
        assertFailsWith<IllegalArgumentException> { EvmWallet.accountFrom(export(mainnet, 0, generator, path = "m/44'/60'/0'/0")) }
        assertFailsWith<IllegalArgumentException> { EvmWallet.accountFrom(export(mainnet, 0, generator, path = "m/44'/60'/0/0/0")) }
        // The codec takes the uncompressed key, which an Ethereum address is derived from. The compressed form (02 and X) is refused.
        assertFailsWith<IllegalArgumentException> { EvmWallet.accountFrom(export(mainnet, 0, byteArrayOf(0x02) + generator.copyOfRange(1, 33))) }
    }

    @Test
    fun refusesAnEthereumNetworkTheManifestDoesNotName() {
        assertFailsWith<IllegalArgumentException> { EvmWallet.accountFrom(export(ChainId("eip155:56"), 0, generator)) }
        assertEquals(1, EvmWallet.accountFrom(export(mainnet, 0, generator)).size)
        assertEquals(1, EvmWallet.accountFrom(export(monad, 0, generator)).size)
    }

    @Test
    fun theManifestPassesTheRegistryAndServesTheThreeNetworks() {
        val registry = ChainRegistry(listOf(EvmWallet))

        assertEquals(listOf(mainnet, monad, sepolia), registry.networks().map { it.chainId })
        assertEquals(listOf(60), registry.networks().map { it.slip44 }.distinct())
    }

    private fun export(
        chain: ChainId,
        index: Int,
        key: ByteArray,
        path: String? = null,
        curve: Curve = Curve.SECP256K1,
    ): AccountExport {
        val signerPath = if (path == null) EvmWallet.pathOf(index) else parsePath(path)
        return AccountExport(chain, SignerRef(FINGERPRINT, signerPath), PublicKey(curve, key), AddressKind.DEFAULT)
    }

    /** A path written as m/44'/60'/0'/0/0, for the refusals. Hardened levels carry a trailing apostrophe. */
    private fun parsePath(text: String): List<PathStep> =
        text.removePrefix("m/").split('/').map { level -> PathStep(level.removeSuffix("'").toInt(), level.endsWith('\'')) }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private companion object {
        const val FINGERPRINT = 0x3ac3d8f0
    }
}
