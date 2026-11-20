package app.airvault.chains.bitcoin.wallet

import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.bitcoin.bitcoinTestnet4
import app.airvault.core.model.AccountId
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
import kotlin.test.assertTrue

class BitcoinWalletTest {
    // The BIP84 vector for m/84'/0'/0'/0/0 from github.com/bitcoin/bips, as shared/src/test/resources/vectors/bitcoin/vectors.txt has it.
    private val receiveKey = hex("0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c")
    private val receiveAddress = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

    private val fingerprint = 1234

    @Test
    fun encodesTheKeyAsItsNativeSegwitAddressAndParsesIt() {
        assertEquals(receiveAddress, BitcoinWallet.addresses.fromPublicKey(bitcoinMainnet, receiveKey, AddressKind.DEFAULT))
        assertEquals(
            AddressResult.Valid(receiveAddress, AddressKind.DEFAULT),
            BitcoinWallet.addresses.parse(bitcoinMainnet, receiveAddress),
        )
    }

    @Test
    fun accountFromTurnsTheFirstReceiveKeyIntoItsAccount() {
        val export = export(bitcoinMainnet, BitcoinWallet.pathOf(bitcoinMainnet, 0))

        val account = BitcoinWallet.accountFrom(export).single()

        assertEquals(AccountId.of(bitcoinMainnet, receiveAddress), account.id)
        assertEquals(export.signer, account.signer)
        assertEquals(receiveKey.toList(), account.publicKey.bytes.toList())
        assertEquals(AddressKind.DEFAULT, account.kind)
    }

    @Test
    fun pathOfAccountNIsTheBip84ReceiveKeyAndAccountFromAcceptsIt() {
        assertEquals(
            listOf(PathStep(84, true), PathStep(0, true), PathStep(3, true), PathStep(0, false), PathStep(0, false)),
            BitcoinWallet.pathOf(bitcoinMainnet, 3),
        )
        for (index in 0..2) {
            assertEquals(1, BitcoinWallet.accountFrom(export(bitcoinMainnet, BitcoinWallet.pathOf(bitcoinMainnet, index))).size)
        }
    }

    @Test
    fun testnet4UsesCoinTypeOneAndItsOwnAddresses() {
        assertEquals(
            listOf(PathStep(84, true), PathStep(1, true), PathStep(0, true), PathStep(0, false), PathStep(0, false)),
            BitcoinWallet.pathOf(bitcoinTestnet4, 0),
        )
        val account = BitcoinWallet.accountFrom(export(bitcoinTestnet4, BitcoinWallet.pathOf(bitcoinTestnet4, 0))).single()
        assertTrue(account.id.caip10.startsWith("${bitcoinTestnet4.caip2}:tb1q"), account.id.caip10)
    }

    @Test
    fun refusesAnExportThatIsNotANativeSegwitAccountKey() {
        val account0 = BitcoinWallet.pathOf(bitcoinMainnet, 0)
        val legacy = listOf(PathStep(44, true), PathStep(0, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))
        val accountLevel = listOf(PathStep(84, true), PathStep(0, true), PathStep(0, true))
        val testnetCoin = listOf(PathStep(84, true), PathStep(1, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))

        assertFailsWith<IllegalArgumentException> { BitcoinWallet.accountFrom(export(ChainId("eip155:1"), account0)) }
        assertFailsWith<IllegalArgumentException> { BitcoinWallet.accountFrom(export(bitcoinMainnet, account0, key = ByteArray(32))) }
        assertFailsWith<IllegalArgumentException> {
            BitcoinWallet.accountFrom(export(bitcoinMainnet, account0, curve = Curve.ED25519_BIP32))
        }
        assertFailsWith<IllegalArgumentException> { BitcoinWallet.accountFrom(export(bitcoinMainnet, legacy)) }
        assertFailsWith<IllegalArgumentException> { BitcoinWallet.accountFrom(export(bitcoinMainnet, accountLevel)) }
        assertFailsWith<IllegalArgumentException> { BitcoinWallet.accountFrom(export(bitcoinMainnet, testnetCoin)) }
    }

    private fun export(
        chain: ChainId,
        path: List<PathStep>,
        key: ByteArray = receiveKey,
        curve: Curve = Curve.SECP256K1,
    ): AccountExport = AccountExport(chain, SignerRef(fingerprint, path), PublicKey(curve, key), null, AddressKind.DEFAULT)

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
