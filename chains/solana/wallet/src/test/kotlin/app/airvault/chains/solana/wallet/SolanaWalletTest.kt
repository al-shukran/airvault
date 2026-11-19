package app.airvault.chains.solana.wallet

import app.airvault.chains.solana.SolanaAddressCodec
import app.airvault.core.model.AccountId
import app.airvault.core.model.AddressError
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainId
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SolanaWalletTest {
    private val mainnet = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp")
    private val devnet = ChainId("solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1")
    private val testnet = ChainId("solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3z")

    private val zeroKey = ByteArray(32)
    private val zeroAddress = "11111111111111111111111111111111"

    // The key 00 01 .. 1f, whose address is computed independently in python (base58 of the 32 bytes).
    private val rampKey = ByteArray(32) { it.toByte() }
    private val rampAddress = "1thX6LZfHDZZKUs92febYZhYRcXddmzfzF2NvTkPNE"

    @Test
    fun encodesAKeyAsItsAddress() {
        assertEquals(zeroAddress, SolanaAddressCodec.fromPublicKey(mainnet, zeroKey))
        assertEquals(rampAddress, SolanaAddressCodec.fromPublicKey(devnet, rampKey))
    }

    @Test
    fun acceptsACanonicalAddressOnAnyCluster() {
        assertEquals(AddressResult.Valid(rampAddress, AddressKind.DEFAULT), SolanaWallet.addresses.parse(devnet, rampAddress))
        assertEquals(AddressResult.Valid(zeroAddress, AddressKind.DEFAULT), SolanaWallet.addresses.parse(testnet, zeroAddress))
    }

    @Test
    fun refusesAnAddressWithTheWrongLengthCharsetOrNetwork() {
        assertEquals(AddressResult.Invalid(AddressError.LENGTH), SolanaWallet.addresses.parse(mainnet, "1".repeat(31)))
        assertEquals(AddressResult.Invalid(AddressError.LENGTH), SolanaWallet.addresses.parse(mainnet, "z".repeat(44)))
        assertEquals(AddressResult.Invalid(AddressError.CHARSET), SolanaWallet.addresses.parse(mainnet, "0" + rampAddress.drop(1)))
        assertEquals(AddressResult.Invalid(AddressError.WRONG_NETWORK), SolanaWallet.addresses.parse(ChainId("eip155:1"), rampAddress))
    }

    @Test
    fun accountFromTurnsAnExportedKeyIntoItsAccountOnTheClusterItNames() {
        val export = export(chain = mainnet, index = 0, key = zeroKey)

        val account = SolanaWallet.accountFrom(export).single()

        assertEquals(AccountId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp:$zeroAddress"), account.id)
        assertEquals(export.signer, account.signer)
        assertEquals(zeroKey.toList(), account.publicKey.bytes.toList())
        assertEquals(AddressKind.DEFAULT, account.kind)
    }

    @Test
    fun theSameKeyGivesTheSameAddressOnEachCluster() {
        val onDevnet = SolanaWallet.accountFrom(export(chain = devnet, index = 2, key = rampKey)).single()
        val onTestnet = SolanaWallet.accountFrom(export(chain = testnet, index = 2, key = rampKey)).single()

        assertEquals(rampAddress, onDevnet.id.address)
        assertEquals(rampAddress, onTestnet.id.address)
        assertEquals(devnet, onDevnet.id.chainId)
        assertEquals(testnet, onTestnet.id.chainId)
    }

    @Test
    fun pathOfAccountNIsTheManifestAccountPathAndAccountFromAcceptsIt() {
        assertEquals(listOf(PathStep(44, true), PathStep(501, true), PathStep(3, true)), SolanaWallet.pathOf(3))
        for (index in 0..2) {
            assertEquals(1, SolanaWallet.accountFrom(export(mainnet, index, zeroKey)).size)
        }
    }

    @Test
    fun refusesAnExportThatIsNotASolanaAccountKey() {
        assertFailsWith<IllegalArgumentException> { SolanaWallet.accountFrom(export(ChainId("eip155:1"), 0, zeroKey)) }
        assertFailsWith<IllegalArgumentException> { SolanaWallet.accountFrom(export(mainnet, 0, ByteArray(31))) }
        assertFailsWith<IllegalArgumentException> { SolanaWallet.accountFrom(export(mainnet, 0, zeroKey, path = "m/44'/283'/0'")) }
        assertFailsWith<IllegalArgumentException> { SolanaWallet.accountFrom(export(mainnet, 0, zeroKey, path = "m/44'/501'/0'/0'")) }
        assertFailsWith<IllegalArgumentException> { SolanaWallet.accountFrom(export(mainnet, 0, zeroKey, path = "m/44'/501'/0")) }
        assertFailsWith<IllegalArgumentException> {
            SolanaWallet.accountFrom(export(mainnet, 0, zeroKey, curve = Curve.ED25519_BIP32))
        }
    }

    private fun export(
        chain: ChainId,
        index: Int,
        key: ByteArray,
        path: String? = null,
        curve: Curve = Curve.ED25519_SLIP10,
    ): AccountExport {
        val signerPath = if (path == null) SolanaWallet.pathOf(index) else parsePath(path)
        return AccountExport(chain, SignerRef(FINGERPRINT, signerPath), PublicKey(curve, key), AddressKind.DEFAULT)
    }

    /** A path written as m/44'/501'/0', for the refusals. Hardened levels carry a trailing apostrophe. */
    private fun parsePath(text: String): List<PathStep> =
        text.removePrefix("m/").split('/').map { level -> PathStep(level.removeSuffix("'").toInt(), level.endsWith('\'')) }

    private companion object {
        const val FINGERPRINT = 0x3ac3d8f0
    }
}
