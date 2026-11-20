package app.lensly.app.sign

import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.Path
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import org.junit.Test

private const val FINGERPRINT = 0x1a2b3c4d
private const val ADDRESS = "0x0000000000000000000000000000000000000000"

private val SEED by lazy { Mnemonic.toSeed(List(23) { "abandon" } + "art") }
private val TESTNET = BtcAccount.fromSeed(SEED, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
private val TESTNET_PATH = nativeSegwitAccountOf(BtcNetwork.TESTNET4)
private val MAINNET_PATH = nativeSegwitAccountOf(BtcNetwork.MAINNET)
private val ACCOUNT_PATH = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true))

/** The path m/84'/coin'/0' of the first native segwit Bitcoin Account on [network], which a wallet stores for it. */
private fun nativeSegwitAccountOf(network: BtcNetwork) =
    Path(listOf(PathStep(ScriptType.NATIVE_SEGWIT.purpose, true), PathStep(network.coinType, true), PathStep(0, true)))

/** A Mnemonic wallet with [fingerprint] that stores the accounts at [storedPaths]. */
private fun mnemonic(id: String, fingerprint: Int, storedPaths: List<Path> = emptyList()) = WalletView(
    id = id,
    masterFingerprint = fingerprint,
    accountCount = 1,
    autoNameBase = id,
    addressAt = { _, _ -> ADDRESS },
    storedAccountPaths = storedPaths,
)

private fun singleKey(id: String) = WalletView.singleKey(id = id, address = ADDRESS, autoNameBase = id)

/** An app request with [header] and no body, which routing reads only through the header of a request that is not Ethereum. */
private fun appRequest(header: RequestHeader) = AppRequest(header, ByteArray(0))

private fun ethereum(fingerprint: Int?) = appRequestOf(
    EthSignRequest(
        requestId = ByteArray(16) { 1 },
        signData = byteArrayOf(1),
        dataType = DataType.PERSONAL_MESSAGE,
        chainId = 1L,
        derivationPath = listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(0, false)),
        sourceFingerprint = fingerprint,
        address = null,
        origin = null,
    ),
)

private fun solana(fingerprint: Int?) = appRequest(
    RequestHeader(
        app = solanaManifest.id,
        chain = SOLANA_MAINNET,
        kind = RequestKind.TRANSACTION,
        signers = listOf(SignerRef(fingerprint, ACCOUNT_PATH)),
        requestId = ByteArray(16) { 1 },
    ),
)

private fun algorand(fingerprint: Int?) = appRequest(
    RequestHeader(
        app = algorandManifest.id,
        chain = algorandManifest.networks.first { !it.testnet }.chainId,
        kind = RequestKind.TRANSACTION,
        signers = listOf(SignerRef(fingerprint, ACCOUNT_PATH)),
        requestId = ByteArray(16) { 1 },
    ),
)

/** The reference of an account of the first testnet4 native segwit account's path, under the master fingerprint [fingerprint]. */
private fun testnetAccount(fingerprint: Int = TESTNET.masterFingerprint.toInt()) =
    SignerRef(fingerprint, listOf(PathStep(84, true), PathStep(1, true), PathStep(0, true)))

/** A Bitcoin request whose inputs name [signers], one reference per account. The default names the first testnet4 native segwit account. */
private fun bitcoin(signers: List<SignerRef> = listOf(testnetAccount())): AppRequest = appRequest(
    RequestHeader(
        app = bitcoinManifest.id,
        chain = bitcoinManifest.networks.single { it.testnet }.chainId,
        kind = RequestKind.TRANSACTION,
        signers = signers,
        requestId = null,
    ),
)

class ScanRouteTest {

    @Test
    fun anEthereumRequestRoutesToTheWalletOfItsFingerprint() {
        val wallets = listOf(mnemonic("eth", FINGERPRINT), mnemonic("other", FINGERPRINT xor 1))

        assertEquals(WalletMatch.One("eth"), routeScan(ethereum(FINGERPRINT), wallets))
    }

    @Test
    fun anEthereumRequestWithNoWalletOfItsFingerprintRoutesToNone() {
        assertEquals(WalletMatch.None, routeScan(ethereum(FINGERPRINT), listOf(mnemonic("other", FINGERPRINT xor 1))))
    }

    @Test
    fun aBitcoinRequestRoutesToTheWalletOfTheAccountItSpendsFrom() {
        val wallets = listOf(mnemonic("btc", TESTNET.masterFingerprint.toInt(), listOf(TESTNET_PATH)))

        assertEquals(WalletMatch.One("btc"), routeScan(bitcoin(), wallets))
    }

    @Test
    fun aBitcoinRequestOnAnotherNetworkRoutesToNone() {
        val wallets = listOf(mnemonic("mainnet", TESTNET.masterFingerprint.toInt(), listOf(MAINNET_PATH)))

        assertEquals(WalletMatch.None, routeScan(bitcoin(), wallets))
    }

    @Test
    fun aBitcoinRequestOfAnotherFingerprintRoutesToNone() {
        val wallets = listOf(mnemonic("other", TESTNET.masterFingerprint.toInt() xor 1, listOf(TESTNET_PATH)))

        assertEquals(WalletMatch.None, routeScan(bitcoin(), wallets))
    }

    @Test
    fun aSolanaRequestRoutesToTheWalletOfItsFingerprint() {
        val wallets = listOf(mnemonic("sol", FINGERPRINT), mnemonic("other", FINGERPRINT xor 1))

        assertEquals(WalletMatch.One("sol"), routeScan(solana(FINGERPRINT), wallets))
    }

    @Test
    fun aSolanaRequestWithNoWalletOfItsFingerprintOrNoFingerprintRoutesToNone() {
        val wallets = listOf(mnemonic("sol", FINGERPRINT))

        assertEquals(WalletMatch.None, routeScan(solana(FINGERPRINT xor 1), wallets))
        assertEquals(WalletMatch.None, routeScan(solana(null), wallets))
    }

    @Test
    fun anAlgorandRequestRoutesToTheWalletOfItsFingerprint() {
        val wallets = listOf(mnemonic("algo", FINGERPRINT), mnemonic("other", FINGERPRINT xor 1))

        assertEquals(WalletMatch.One("algo"), routeScan(algorand(FINGERPRINT), wallets))
    }

    @Test
    fun anAlgorandRequestWithNoWalletOfItsFingerprintRoutesToNone() {
        assertEquals(WalletMatch.None, routeScan(algorand(FINGERPRINT), listOf(mnemonic("other", FINGERPRINT xor 1))))
    }

    @Test
    fun aSingleKeyWalletIsForNoBitcoinSolanaOrAlgorandRequest() {
        val wallets = listOf(singleKey("key"))

        assertEquals(WalletMatch.None, routeScan(bitcoin(), wallets))
        assertEquals(WalletMatch.None, routeScan(solana(FINGERPRINT), wallets))
        assertEquals(WalletMatch.None, routeScan(algorand(FINGERPRINT), wallets))
    }

    @Test
    fun twoWalletsOfTheFingerprintOfASolanaRequestRouteToSeveral() {
        val wallets = listOf(mnemonic("first", FINGERPRINT), mnemonic("second", FINGERPRINT))

        assertEquals(WalletMatch.Several(2), routeScan(solana(FINGERPRINT), wallets))
    }

    @Test
    fun aBitcoinRequestWithAnOutsideCoSignerRoutesToTheWalletOfItsOwnAccount() {
        val wallets = listOf(mnemonic("btc", TESTNET.masterFingerprint.toInt(), listOf(TESTNET_PATH)))
        val outside = testnetAccount(TESTNET.masterFingerprint.toInt() xor 1)

        assertEquals(WalletMatch.One("btc"), routeScan(bitcoin(listOf(testnetAccount(), outside)), wallets))
    }

    @Test
    fun aBitcoinRequestClaimingAccountsOfTwoWalletsRoutesToSeveral() {
        val other = TESTNET.masterFingerprint.toInt() xor 1
        val wallets = listOf(
            mnemonic("btc", TESTNET.masterFingerprint.toInt(), listOf(TESTNET_PATH)),
            mnemonic("other", other, listOf(TESTNET_PATH)),
        )

        assertEquals(WalletMatch.Several(2), routeScan(bitcoin(listOf(testnetAccount(), testnetAccount(other))), wallets))
    }

    @Test
    fun anEmptyVaultRoutesEveryChainToNone() {
        listOf(ethereum(FINGERPRINT), bitcoin(), solana(FINGERPRINT), algorand(FINGERPRINT)).forEach { request ->
            assertEquals(WalletMatch.None, routeScan(request, emptyList()))
        }
    }
}
