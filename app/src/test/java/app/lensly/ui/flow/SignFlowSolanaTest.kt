package app.lensly.ui.flow

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.evm.signer.ParsedRequest
import app.lensly.app.export.partsOf
import app.lensly.app.sign.AlgorandReviewEntry
import app.lensly.app.sign.BitcoinReviewEntry
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.SolanaReviewEntry
import app.lensly.app.sign.WalletView
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaInstruction
import app.lensly.core.solana.SolanaRequestCheck
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignatureEncoder
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.ui.sign.SAMPLE_SIGNER
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.web3j.crypto.MnemonicUtils

/**
 * The Solana sign flow: a scanned sol-sign-request is reviewed, approved, checked with the App Password, and answered with a
 * sol-signature. The ports are a fake. The scan goes through the real Parser Sandbox parser.
 */
class SignFlowSolanaTest {

    @Test
    fun aSolanaRequestIsReviewedThenSignedAndRecordedAsASignature() = runBlocking {
        val ports = SolanaPorts()
        val flow = SignFlow(ports, walletId = WALLET)

        flow.onScanned(partOf(requestCbor(transfer())))

        val review = assertIs<SignStep.SolanaReview>(flow.state.value)
        assertEquals(0, review.ready.review.accountIndex)
        flow.approve()
        flow.confirm("the password".toCharArray())

        val signature = assertIs<SignStep.Signature>(flow.state.value).parts
        assertTrue(Ed25519Signer.verify(SOLANA_KEYS[0], transfer(), signatureOf(signature)))
        assertEquals(listOf(ReviewOutcome.SIGNED), ports.records.map { it.outcome })
        assertEquals(1, ports.signCalls)
    }

    @Test
    fun aSplTransferCheckedIsReviewedSignedAndVerifiedWithTheAccountKey() = runBlocking {
        val ports = SolanaPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        val message = splTransferChecked(SOLANA_KEYS[0])

        flow.onScanned(partOf(requestCbor(message)))

        val review = assertIs<SignStep.SolanaReview>(flow.state.value)
        assertTrue(review.ready.review.instructions.any { it is SolanaInstruction.TokenTransferChecked })
        flow.approve()
        flow.confirm("the password".toCharArray())

        val signature = assertIs<SignStep.Signature>(flow.state.value).parts
        assertTrue(Ed25519Signer.verify(SOLANA_KEYS[0], message, signatureOf(signature)))
        assertEquals(listOf(ReviewOutcome.SIGNED), ports.records.map { it.outcome })
    }

    @Test
    fun aSolanaRequestScannedWithoutAWalletReachesTheReviewOfTheWalletItIsFor() = runBlocking {
        val view = WalletView(id = WALLET, masterFingerprint = FINGERPRINT, accountCount = 1, autoNameBase = "Solana", addressAt = { "" })
        val flow = SignFlow(SolanaPorts(wallets = listOf(view)), walletId = null)

        flow.onScanned(partOf(requestCbor(transfer())))

        assertEquals(WALLET, assertIs<SignStep.SolanaReview>(flow.state.value).ready.walletId)
    }

    @Test
    fun aSolanaRequestNoWalletIsForIsRefusedInPlainWordsBeforeAnyCheck() = runBlocking {
        val other = WalletView(id = "other", masterFingerprint = FINGERPRINT xor 1, accountCount = 1, autoNameBase = "Other", addressAt = { "" })
        val ports = SolanaPorts(wallets = listOf(other))
        val flow = SignFlow(ports, walletId = null)

        flow.onScanned(partOf(requestCbor(transfer())))

        assertEquals(SignStep.Refused("No wallet in this vault can sign this request"), flow.state.value)
        assertEquals(0, ports.prepareCalls)
        assertTrue(ports.records.isEmpty())
    }

    /** A path other than m/44'/501'/N' is refused, and the refusal is recorded under the wallet with its reason. */
    @Test
    fun aWrongPathIsRefusedAndRecordedWithItsReason() = runBlocking {
        val ports = SolanaPorts()
        val flow = SignFlow(ports, walletId = WALLET)

        flow.onScanned(partOf(requestCbor(transfer(), path = listOf(44L to true, 501L to true, 0L to true, 0L to true))))

        assertEquals(SignStep.Refused(SolanaRequestCheck.PATH_REFUSED), flow.state.value)
        assertEquals(listOf(ReviewOutcome.REFUSED), ports.records.map { it.outcome })
        assertEquals(SolanaRequestCheck.PATH_REFUSED, ports.records.single().refusal)
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun aRequestWithoutARequestIdIsRefused() = runBlocking {
        val ports = SolanaPorts()
        val flow = SignFlow(ports, walletId = WALLET)

        flow.onScanned(partOf(requestCbor(transfer(), requestId = null)))

        assertIs<SignStep.Refused>(flow.state.value)
        assertEquals(listOf(ReviewOutcome.REFUSED), ports.records.map { it.outcome })
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun aWrongPasswordSignsNothingAndStaysOnTheHoldToSign() = runBlocking {
        val ports = SolanaPorts(password = PasswordCheck.Rejected)
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(transfer())))

        flow.approve()
        flow.confirm("wrong".toCharArray())

        assertEquals(false, assertIs<SignStep.SolanaConfirming>(flow.state.value).busy)
        assertEquals(0, ports.signCalls)
        assertTrue(ports.records.isEmpty())
    }

    @Test
    fun leavingAReviewedRequestRecordsItAsCancelled() = runBlocking {
        val ports = SolanaPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(transfer())))

        flow.leave()

        assertEquals(SignStep.Closed, flow.state.value)
        assertEquals(listOf(ReviewOutcome.CANCELLED), ports.records.map { it.outcome })
        assertEquals(0, ports.signCalls)
    }

    /** A v0 message that loads an account from a lookup table is reviewed with the lookup warning, and holds its signature for the read. */
    @Test
    fun aLookupTableRecipientIsRefusedInTheFlow() = runBlocking {
        val flow = SignFlow(SolanaPorts(), walletId = WALLET)

        flow.onScanned(partOf(requestCbor(v0FromLookupTable(payer = SOLANA_KEYS[0]))))

        assertEquals(SignStep.Refused(SolanaRequestCheck.LOOKUP_RECIPIENT_REFUSAL), flow.state.value)
    }

    /** The fake ports. The wallet holds the Solana accounts of mnemonic A, and the App Password check is [password]. */
    private class SolanaPorts(
        private val keys: List<ByteArray> = SOLANA_KEYS,
        private val password: PasswordCheck = PasswordCheck.Accepted,
        private val parser: RequestParser = inProcessSandboxParser,
        private val wallets: List<WalletView> = emptyList(),
    ) : SignFlowPorts {
        val records = mutableListOf<SolanaReviewEntry>()
        var prepareCalls = 0
        var signCalls = 0

        override suspend fun wallets(): List<WalletView> = wallets

        override suspend fun scan(parts: List<String>): ScanReply = parser.scan(parts)

        override suspend fun prepare(walletId: String, parsed: ParsedRequest): Prepared =
            error("an EVM request is not expected in the Solana tests")

        override suspend fun privateKey(walletId: String, accountIndex: Int): ByteArray =
            error("an EVM key is not read in the Solana tests")

        override suspend fun checkPassword(password: CharArray): PasswordCheck = this.password

        override suspend fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> =
            error("an EVM request is not expected in the Solana tests")

        override suspend fun record(entry: ReviewEntry): Unit = error("an EVM request is not expected in the Solana tests")

        override suspend fun recordBitcoin(entry: BitcoinReviewEntry): Unit = error("a Bitcoin request is not expected here")

        override suspend fun bitcoinPrepare(walletId: String, psbt: ByteArray): BitcoinPrepared =
            error("a Bitcoin request is not expected here")

        override suspend fun bitcoinSign(ready: BitcoinPrepared.Ready, unverifiedConfirmed: Boolean): List<String> =
            error("a Bitcoin request is not expected here")

        override suspend fun solanaPrepare(walletId: String, request: SolSignRequest): SolanaPrepared {
            prepareCalls++
            if (request.sourceFingerprint != FINGERPRINT) return SolanaPrepared.Refused("This request is for a different wallet")
            return when (val checked = SolanaRequestCheck.check(request, keys)) {
                is SolanaRequestCheck.Result.Ok -> SolanaPrepared.Ready(walletId, request, checked.review, SAMPLE_SIGNER, emptyMap())
                is SolanaRequestCheck.Result.Refused -> SolanaPrepared.Refused(checked.reason)
            }
        }

        override suspend fun solanaSign(ready: SolanaPrepared.Ready): List<String> {
            signCalls++
            val key = SolanaAccount.node(SEED, ready.review.accountIndex).use { it.privateKey() }
            try {
                val signature = Ed25519Signer.sign(key, ready.request.signData)
                return partsOf(SolSignatureEncoder.encode(assertNotNull(ready.request.requestId), signature))
            } finally {
                key.fill(0)
            }
        }

        override suspend fun recordSolana(entry: SolanaReviewEntry) {
            records += entry
        }

        override suspend fun algorandPrepare(walletId: String, request: AlgoSignRequest): AlgorandPrepared =
            error("an Algorand request is not expected in the Solana tests")

        override suspend fun algorandSign(ready: AlgorandPrepared.Ready): List<String> =
            error("an Algorand request is not expected in the Solana tests")

        override suspend fun recordAlgorand(entry: AlgorandReviewEntry): Unit =
            error("an Algorand request is not expected in the Solana tests")
    }

    private companion object {
        const val WALLET = "solana-wallet"
        const val FINGERPRINT = 0x12121212
        const val SYSTEM_ADDRESS = "11111111111111111111111111111111"
        const val TABLE = "CUDp5gcuAm64fAXUdWviywAdmrnTbrMbFPmP5u7tUL8q"
        const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val REQUEST_ID = ByteArray(16) { 0x11 }
        const val MNEMONIC_A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val SEED: ByteArray = MnemonicUtils.generateSeed(MNEMONIC_A, "")
        val SOLANA_KEYS: List<ByteArray> = (0..1).map { SolanaAccount.publicKey(SEED, it) }

        /** A legacy transfer of 1000 lamports from account 0 of the wallet to a stranger, so account 0 is its one signer. */
        fun transfer(): ByteArray = legacyTransfer(listOf(SOLANA_KEYS[0], ByteArray(32) { 9 }, Base58.decode(SYSTEM_ADDRESS)))

        /** A v0 message that moves 1000 lamports from [payer] to account 2, which is loaded from [TABLE] at index 0. */
        fun v0FromLookupTable(payer: ByteArray): ByteArray {
            val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(1_000L).array()
            val out = ByteArrayOutputStream()
            out.write(0x80)
            out.write(byteArrayOf(1, 0, 1))
            out.write(2)
            out.write(payer)
            out.write(Base58.decode(SYSTEM_ADDRESS))
            out.write(ByteArray(32) { 7 })
            out.write(1)
            out.write(1)
            out.write(2)
            out.write(byteArrayOf(0, 2))
            out.write(data.size)
            out.write(data)
            out.write(1)
            out.write(Base58.decode(TABLE))
            out.write(1)
            out.write(0)
            out.write(0)
            return out.toByteArray()
        }

        fun legacyTransfer(keys: List<ByteArray>): ByteArray {
            val data = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(1_000L).array()
            val out = java.io.ByteArrayOutputStream()
            out.write(byteArrayOf(1, 0, 1))
            out.write(keys.size)
            keys.forEach { out.write(it) }
            out.write(ByteArray(32) { 7 })
            out.write(byteArrayOf(1, 2, 2, 0, 1, 12))
            out.write(data)
            return out.toByteArray()
        }

        /**
         * A legacy SPL TransferChecked of 1000 units of USDC, with 6 decimals, from one token account to another. The fee payer [payer] is
         * the one signer and the authority. The Token program and the mint are readonly, and the two token accounts are writable.
         */
        fun splTransferChecked(payer: ByteArray): ByteArray {
            val data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put(12).putLong(1_000L).put(6).array()
            val out = ByteArrayOutputStream()
            // Header: one required signature, no readonly signed accounts, and two readonly unsigned accounts (the mint and the program).
            out.write(byteArrayOf(1, 0, 2))
            // Keys: the fee payer, the source, the destination, the mint, and the Token program.
            out.write(5)
            out.write(payer)
            out.write(ByteArray(32) { 5 })
            out.write(ByteArray(32) { 6 })
            out.write(Base58.decode(USDC_MINT))
            out.write(Base58.decode(TOKEN_PROGRAM))
            out.write(ByteArray(32) { 7 })
            // One instruction: the program is key 4, its accounts are source 1, mint 3, destination 2 and authority 0, then the data.
            out.write(1)
            out.write(4)
            out.write(4)
            out.write(byteArrayOf(1, 3, 2, 0))
            out.write(data.size)
            out.write(data)
            return out.toByteArray()
        }

        /** A sol-sign-request for [signData] on account 0 of the wallet, as a wallet sends it. Pass null for [requestId] to leave it out. */
        fun requestCbor(
            signData: ByteArray,
            path: List<Pair<Long, Boolean>> = listOf(44L to true, 501L to true, 0L to true),
            requestId: ByteArray? = REQUEST_ID,
        ): ByteArray {
            val components = path.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }
            val keypath = Cbor.Tagged(
                304,
                Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(FINGERPRINT.toLong()))),
            )
            val fields = listOfNotNull(
                requestId?.let { Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(it)) },
                Cbor.Uint(2L) to Cbor.Bytes(signData),
                Cbor.Uint(3L) to keypath,
                Cbor.Uint(5L) to Cbor.Text("solflare"),
                Cbor.Uint(6L) to Cbor.Uint(1L),
            )
            return Cbor.encode(Cbor.Dict(fields))
        }

        /** The one frame of a request that fits in one QR code. */
        fun partOf(cbor: ByteArray): String = framesOf(UR("sol-sign-request", cbor), maxFragment = 4096).first()

        /** The 64-byte signature inside a single `ur:sol-signature` frame. */
        fun signatureOf(parts: List<String>): ByteArray {
            val scanner = UrScanner()
            assertTrue(scanner.receive(parts.single()))
            val ur = assertNotNull(scanner.result())
            assertEquals("sol-signature", ur.type)
            val fields = (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (key, value) ->
                (key as Cbor.Uint).value to value
            }
            return (fields.getValue(2L) as Cbor.Bytes).value
        }
    }
}
