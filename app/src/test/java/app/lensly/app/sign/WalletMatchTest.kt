package app.lensly.app.sign

import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import org.junit.Test
import kotlin.test.assertEquals

private const val FINGERPRINT = 0x1a2b3c4d

/** The address of a Single Key, as a wallet shows it, and as a request carries it in bytes. */
private const val SINGLE_ADDRESS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"
private val SINGLE_ADDRESS_BYTES = "2c7536e3605d9c16a7a3d7b1898e529396a65c23".chunked(2)
    .map { it.toInt(16).toByte() }
    .toByteArray()

/** A Mnemonic wallet whose master fingerprint is [fingerprint]. Its addresses are not read by the matcher. */
private fun mnemonic(id: String, fingerprint: Int) = WalletView(
    id = id,
    masterFingerprint = fingerprint,
    accountCount = 1,
    autoNameBase = id,
    addressAt = { "0x0000000000000000000000000000000000000000" },
)

/** A request as a wallet sends it. Pass null for a field the wallet leaves out. */
private fun request(fingerprint: Int?, address: ByteArray? = null) = EthSignRequest(
    requestId = ByteArray(16) { 1 },
    signData = byteArrayOf(1),
    dataType = DataType.PERSONAL_MESSAGE,
    chainId = 1L,
    derivationPath = listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(0, false)),
    sourceFingerprint = fingerprint,
    address = address,
    origin = null,
)

class WalletMatchTest {

    @Test
    fun aRequestWithTheFingerprintOfOneWalletMatchesThatWallet() {
        val wallets = listOf(mnemonic("first", FINGERPRINT), mnemonic("second", FINGERPRINT xor 1))

        assertEquals(WalletMatch.One("first"), matchWallet(request(FINGERPRINT), wallets))
        assertEquals(WalletMatch.One("second"), matchWallet(request(FINGERPRINT xor 1), wallets))
    }

    @Test
    fun aRequestWithNoMatchingFingerprintMatchesNoWallet() {
        val wallets = listOf(mnemonic("first", FINGERPRINT))

        assertEquals(WalletMatch.None, matchWallet(request(FINGERPRINT xor 1), wallets))
    }

    @Test
    fun aRequestThatNamesNoFingerprintMatchesNoMnemonicWallet() {
        val wallets = listOf(mnemonic("first", FINGERPRINT))

        assertEquals(WalletMatch.None, matchWallet(request(fingerprint = null), wallets))
    }

    @Test
    fun aRequestForTwoWalletsWithTheSameFingerprintMatchesSeveral() {
        val wallets = listOf(mnemonic("first", FINGERPRINT), mnemonic("copy", FINGERPRINT), mnemonic("other", 7))

        assertEquals(WalletMatch.Several(2), matchWallet(request(FINGERPRINT), wallets))
    }

    @Test
    fun anEmptyVaultMatchesNoWallet() {
        assertEquals(WalletMatch.None, matchWallet(request(FINGERPRINT), emptyList()))
    }

    @Test
    fun aSingleKeyMatchesByItsAddressWhenTheRequestNamesIt() {
        val key = WalletView.singleKey(id = "key", address = SINGLE_ADDRESS, autoNameBase = "Imported")

        assertEquals(WalletMatch.One("key"), matchWallet(request(fingerprint = null, address = SINGLE_ADDRESS_BYTES), listOf(key)))
    }

    @Test
    fun aSingleKeyIsNotMatchedByAFingerprintOrByNoAddress() {
        val key = WalletView.singleKey(id = "key", address = SINGLE_ADDRESS, autoNameBase = "Imported")

        assertEquals(WalletMatch.None, matchWallet(request(fingerprint = null), listOf(key)))
        assertEquals(WalletMatch.None, matchWallet(request(FINGERPRINT), listOf(key)))
    }

    @Test
    fun aMnemonicWalletIsNotMatchedByAnAddressAlone() {
        val wallets = listOf(mnemonic("first", FINGERPRINT))

        assertEquals(WalletMatch.None, matchWallet(request(fingerprint = null, address = SINGLE_ADDRESS_BYTES), wallets))
    }

    @Test
    fun aMnemonicAndASingleKeyAreEachMatchedByTheirOwnRule() {
        val wallets = listOf(
            mnemonic("first", FINGERPRINT),
            WalletView.singleKey(id = "key", address = SINGLE_ADDRESS, autoNameBase = "Imported"),
        )

        assertEquals(WalletMatch.One("first"), matchWallet(request(FINGERPRINT), wallets))
        assertEquals(WalletMatch.One("key"), matchWallet(request(fingerprint = null, address = SINGLE_ADDRESS_BYTES), wallets))
    }
}
