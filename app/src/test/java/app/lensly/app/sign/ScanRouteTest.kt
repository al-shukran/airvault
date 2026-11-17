package app.lensly.app.sign

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.AlgoSignRequest
import app.lensly.core.ur.AlgoSignType
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
import app.lensly.core.wallet.Mnemonic
import fr.acinq.bitcoin.KeyPath
import org.junit.Test
import kotlin.test.assertEquals

private const val FINGERPRINT = 0x1a2b3c4d
private const val ADDRESS = "0x0000000000000000000000000000000000000000"

private val SEED by lazy { Mnemonic.toSeed(List(23) { "abandon" } + "art") }
private val TESTNET = BtcAccount.fromSeed(SEED, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
private val MAINNET_PATH = BtcAccount.fromSeed(SEED, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT).path
private val ACCOUNT_PATH = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true))

/** A Mnemonic wallet with [fingerprint] and the Bitcoin Accounts at [bitcoinPaths]. */
private fun mnemonic(id: String, fingerprint: Int, bitcoinPaths: List<KeyPath> = emptyList()) = WalletView(
    id = id,
    masterFingerprint = fingerprint,
    accountCount = 1,
    autoNameBase = id,
    addressAt = { ADDRESS },
    bitcoinAccountPaths = bitcoinPaths,
)

private fun singleKey(id: String) = WalletView.singleKey(id = id, address = ADDRESS, autoNameBase = id)

private fun ethereum(fingerprint: Int?) = ScannedRequest.Ethereum(
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

private fun solana(fingerprint: Int?) = ScannedRequest.Solana(
    SolSignRequest(
        requestId = ByteArray(16) { 1 },
        signData = byteArrayOf(1),
        derivationPath = ACCOUNT_PATH,
        sourceFingerprint = fingerprint,
        address = null,
        origin = null,
        signType = SolSignType.TRANSACTION,
    ),
)

private fun algorand(fingerprint: Int?) = ScannedRequest.Algorand(
    AlgoSignRequest(
        requestId = ByteArray(16) { 1 },
        signData = byteArrayOf(1),
        derivationPath = ACCOUNT_PATH,
        sourceFingerprint = fingerprint,
        address = "",
        origin = "",
        signType = AlgoSignType.TRANSACTION,
    ),
)

/** A testnet4 native segwit spend from the first account of [SEED], which names that account's master fingerprint. */
private fun bitcoin(): ScannedRequest.Bitcoin {
    val spend = BtcFixtures.Spend(BtcFixtures.funding(TESTNET, 0, 0, 100_000L), TESTNET, 0, 0)
    return ScannedRequest.Bitcoin(BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(TESTNET, paid = 30_000L, change = 60_000L)))
}

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
        val wallets = listOf(mnemonic("btc", TESTNET.masterFingerprint.toInt(), listOf(TESTNET.path)))

        assertEquals(WalletMatch.One("btc"), routeScan(bitcoin(), wallets))
    }

    @Test
    fun aBitcoinRequestOnAnotherNetworkRoutesToNone() {
        val wallets = listOf(mnemonic("mainnet", TESTNET.masterFingerprint.toInt(), listOf(MAINNET_PATH)))

        assertEquals(WalletMatch.None, routeScan(bitcoin(), wallets))
    }

    @Test
    fun aBitcoinRequestOfAnotherFingerprintRoutesToNone() {
        val wallets = listOf(mnemonic("other", TESTNET.masterFingerprint.toInt() xor 1, listOf(TESTNET.path)))

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
    fun anEmptyVaultRoutesEveryChainToNone() {
        listOf(ethereum(FINGERPRINT), bitcoin(), solana(FINGERPRINT), algorand(FINGERPRINT)).forEach { request ->
            assertEquals(WalletMatch.None, routeScan(request, emptyList()))
        }
    }
}
