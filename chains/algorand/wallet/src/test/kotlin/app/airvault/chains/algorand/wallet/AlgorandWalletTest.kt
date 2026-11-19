package app.airvault.chains.algorand.wallet

import app.airvault.chains.algorand.AlgorandAddressCodec
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

class AlgorandWalletTest {
    private val mainnet = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")
    private val testnet = ChainId("algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDe")

    private val zeroKey = ByteArray(32)
    private val zeroAddress = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY5HFKQ"

    // The key 00 01 .. 1f, whose address is computed independently with SHA-512/256 (python hashlib).
    private val rampKey = ByteArray(32) { it.toByte() }
    private val rampAddress = "AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP7MUPJQE"

    @Test
    fun encodesAKeyAsItsAddress() {
        assertEquals(zeroAddress, AlgorandAddressCodec.fromPublicKey(mainnet, zeroKey))
        assertEquals(rampAddress, AlgorandAddressCodec.fromPublicKey(testnet, rampKey))
    }

    @Test
    fun acceptsACanonicalAddress() {
        assertEquals(AddressResult.Valid(rampAddress, AddressKind.DEFAULT), AlgorandAddressCodec.parse(mainnet, rampAddress))
    }

    @Test
    fun refusesAnAddressWithTheWrongShapeChecksumOrNetwork() {
        assertEquals(AddressResult.Invalid(AddressError.LENGTH), AlgorandAddressCodec.parse(mainnet, rampAddress.dropLast(1)))
        assertEquals(AddressResult.Invalid(AddressError.CHARSET), AlgorandAddressCodec.parse(mainnet, rampAddress.lowercase()))
        assertEquals(AddressResult.Invalid(AddressError.CHECKSUM), AlgorandAddressCodec.parse(mainnet, rampAddress.dropLast(1) + "A"))
        assertEquals(AddressResult.Invalid(AddressError.WRONG_NETWORK), AlgorandAddressCodec.parse(ChainId("eip155:1"), rampAddress))
    }

    @Test
    fun accountFromTurnsAnExportedKeyIntoItsAccountOnTheNetworkItNames() {
        val export = export(chain = mainnet, index = 0, key = zeroKey)

        val account = AlgorandWallet.accountFrom(export).single()

        assertEquals(AccountId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:$zeroAddress"), account.id)
        assertEquals(export.signer, account.signer)
        assertEquals(zeroKey.toList(), account.publicKey.bytes.toList())
        assertEquals(AddressKind.DEFAULT, account.kind)
    }

    @Test
    fun theSameKeyGivesTheSameAddressOnEachNetwork() {
        val onMainnet = AlgorandWallet.accountFrom(export(chain = mainnet, index = 2, key = rampKey)).single()
        val onTestnet = AlgorandWallet.accountFrom(export(chain = testnet, index = 2, key = rampKey)).single()

        assertEquals(rampAddress, onMainnet.id.address)
        assertEquals(rampAddress, onTestnet.id.address)
        assertEquals(testnet, onTestnet.id.chainId)
    }

    @Test
    fun pathOfAccountNIsTheManifestAccountPathAndAccountFromAcceptsIt() {
        assertEquals(listOf(PathStep(44, true), PathStep(283, true), PathStep(3, true), PathStep(0, false), PathStep(0, false)), AlgorandWallet.pathOf(3))
        for (index in 0..2) {
            assertEquals(1, AlgorandWallet.accountFrom(export(mainnet, index, zeroKey)).size)
        }
    }

    @Test
    fun refusesAnExportThatIsNotAnAlgorandAccountKey() {
        assertFailsWith<IllegalArgumentException> { AlgorandWallet.accountFrom(export(ChainId("eip155:1"), 0, zeroKey)) }
        assertFailsWith<IllegalArgumentException> { AlgorandWallet.accountFrom(export(mainnet, 0, ByteArray(31))) }
        assertFailsWith<IllegalArgumentException> {
            AlgorandWallet.accountFrom(export(mainnet, 0, zeroKey, path = "m/44'/60'/0'/0/0"))
        }
        assertFailsWith<IllegalArgumentException> {
            AlgorandWallet.accountFrom(export(mainnet, 0, zeroKey, curve = Curve.SECP256K1))
        }
    }

    private fun export(
        chain: ChainId,
        index: Int,
        key: ByteArray,
        path: String? = null,
        curve: Curve = Curve.ED25519_BIP32,
    ): AccountExport {
        val signerPath = if (path == null) AlgorandWallet.pathOf(index) else parsePath(path)
        return AccountExport(chain, SignerRef(FINGERPRINT, signerPath), PublicKey(curve, key), AddressKind.DEFAULT)
    }

    /** A path written as m/44'/60'/0'/0/0, for the refusals. Hardened levels carry a trailing apostrophe. */
    private fun parsePath(text: String): List<PathStep> =
        text.removePrefix("m/").split('/').map { level -> PathStep(level.removeSuffix("'").toInt(), level.endsWith('\'')) }

    private companion object {
        const val FINGERPRINT = 0x3ac3d8f0
    }
}
