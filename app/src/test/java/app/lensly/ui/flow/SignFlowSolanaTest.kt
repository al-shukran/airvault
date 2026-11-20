package app.lensly.ui.flow

import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolanaInstruction
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.SolanaSigner
import app.airvault.chains.solana.signer.toReview
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.Approval
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ScopedKeyAccess
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.export.partsOf
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.counterpartiesOf
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.core.wallet.WalletKeyAccess
import app.lensly.ui.flow.SolanaRequestFixtures.SYSTEM_ADDRESS
import app.lensly.ui.flow.SolanaRequestFixtures.legacyTransfer
import app.lensly.ui.flow.SolanaRequestFixtures.partOf
import app.lensly.ui.flow.SolanaRequestFixtures.signatureOf
import app.lensly.ui.sign.SAMPLE_SIGNER
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.web3j.crypto.MnemonicUtils
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Solana sign flow: a scanned sol-sign-request is reviewed, approved, checked with the App Password, and answered with a sol-signature. The
 * ports are a fake, and the signature is made by the real [SolanaSigner] over the wallet's key access. The scan goes through the real Parser
 * Sandbox parser. The app's own checks (the requestId, the wallet's fingerprint and the stored key) are tested in VaultSignPortsTest.
 */
class SignFlowSolanaTest {

    @Test
    fun aSolanaRequestIsReviewedThenSignedAndRecordedAsASignature() = runBlocking {
        val ports = SolanaPorts()
        val flow = SignFlow(ports, walletId = WALLET)

        flow.onScanned(partOf(requestCbor(transfer())))

        val review = assertIs<SignStep.AppReview>(flow.state.value)
        assertEquals(0, review.ready.accountIndex)
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

        assertIs<SignStep.AppReview>(flow.state.value)
        assertTrue(ports.reviewed.single().instructions.any { it is SolanaInstruction.TokenTransferChecked })
        flow.approve()
        flow.confirm("the password".toCharArray())

        val signature = assertIs<SignStep.Signature>(flow.state.value).parts
        assertTrue(Ed25519Signer.verify(SOLANA_KEYS[0], message, signatureOf(signature)))
        assertEquals(listOf(ReviewOutcome.SIGNED), ports.records.map { it.outcome })
    }

    @Test
    fun aSolanaRequestScannedWithoutAWalletReachesTheReviewOfTheWalletItIsFor() = runBlocking {
        val view = WalletView(id = WALLET, masterFingerprint = FINGERPRINT, accountCount = 1, autoNameBase = "Solana", addressAt = { _, _ -> "" })
        val flow = SignFlow(SolanaPorts(wallets = listOf(view)), walletId = null)

        flow.onScanned(partOf(requestCbor(transfer())))

        assertEquals(WALLET, assertIs<SignStep.AppReview>(flow.state.value).ready.walletId)
    }

    @Test
    fun aSolanaRequestNoWalletIsForIsRefusedInPlainWordsBeforeAnyCheck() = runBlocking {
        val other = WalletView(id = "other", masterFingerprint = FINGERPRINT xor 1, accountCount = 1, autoNameBase = "Other", addressAt = { _, _ -> "" })
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
    fun aWrongPasswordSignsNothingAndStaysOnTheHoldToSign() = runBlocking {
        val ports = SolanaPorts(password = PasswordCheck.Rejected)
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(transfer())))

        flow.approve()
        flow.confirm("wrong".toCharArray())

        assertEquals(false, assertIs<SignStep.AppConfirming>(flow.state.value).busy)
        assertEquals(0, ports.signCalls)
        assertTrue(ports.records.isEmpty())
    }

    /** A signer that refuses signs nothing, and the refusal is shown and recorded with the reason it gives. */
    @Test
    fun aSigningRefusalSignsNothingAndIsRecordedWithItsReason() = runBlocking {
        val ports = SolanaPorts(signRefusal = KEY_REFUSAL)
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(transfer())))

        flow.approve()
        flow.confirm("the password".toCharArray())

        assertEquals(SignStep.Refused(KEY_REFUSAL), flow.state.value)
        assertEquals(listOf(ReviewOutcome.REFUSED), ports.records.map { it.outcome })
        assertEquals(KEY_REFUSAL, ports.records.single().refusal)
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
        /** The reason the signer refuses with, or null when it signs. */
        private val signRefusal: String? = null,
    ) : SignFlowPorts {
        val records = mutableListOf<AppReviewEntry>()
        val reviewed = mutableListOf<SolanaRequestReview>()
        var prepareCalls = 0
        var signCalls = 0

        override suspend fun wallets(): List<WalletView> = wallets

        override suspend fun scan(parts: List<String>): ScanReply = parser.scan(parts)

        override suspend fun checkPassword(password: CharArray): PasswordCheck = this.password

        override suspend fun appPrepare(walletId: String, request: AppRequest): AppPrepared {
            prepareCalls++
            val sol = when (val decoded = SolSignRequest.decode(request.body)) {
                is SolSignRequest.Result.Ok -> decoded.request
                is SolSignRequest.Result.Rejected -> return AppPrepared.Refused(decoded.reason)
            }
            if (sol.sourceFingerprint != FINGERPRINT) return AppPrepared.Refused("This request is for a different wallet")
            return when (val checked = SolanaRequestCheck.check(sol, keys)) {
                is SolanaRequestCheck.Result.Ok -> {
                    reviewed += checked.review
                    AppPrepared.Ready(
                        walletId = walletId,
                        request = request,
                        review = checked.review.toReview(),
                        accountIndex = checked.review.accountIndex,
                        signer = SAMPLE_SIGNER,
                        addressChain = SOLANA_FAMILY,
                        counterparties = counterpartiesOf(checked.review.toReview()),
                        addressNotes = emptyMap(),
                    )
                }
                is SolanaRequestCheck.Result.Refused -> AppPrepared.Refused(checked.reason)
            }
        }

        override suspend fun appSign(ready: AppPrepared.Ready, approval: Approval): List<String> {
            signCalls++
            signRefusal?.let { throw IllegalArgumentException(it) }
            val response = SolanaSigner.sign(ready.request, approval, ScopedKeyAccess(solanaManifest.keys, WalletKeyAccess(SEED)))
            return partsOf(framesOf(UR(response.urType, response.cbor), DEFAULT_MAX_FRAGMENT))
        }

        override suspend fun recordApp(entry: AppReviewEntry) {
            records += entry
        }
    }

    private companion object {
        const val WALLET = "solana-wallet"
        const val FINGERPRINT = 0x12121212
        const val TABLE = "CUDp5gcuAm64fAXUdWviywAdmrnTbrMbFPmP5u7tUL8q"
        const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val MNEMONIC_A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val KEY_REFUSAL = "Key does not match account"
        val SEED: ByteArray = MnemonicUtils.generateSeed(MNEMONIC_A, "")
        val SOLANA_KEYS: List<ByteArray> = (0..1).map { SolanaAccount.publicKey(SEED, it) }
        val ACCOUNT_0 = listOf(44L to true, 501L to true, 0L to true)

        /** A sol-sign-request for [signData] from this wallet, on the account at [path]. */
        fun requestCbor(signData: ByteArray, path: List<Pair<Long, Boolean>> = ACCOUNT_0): ByteArray =
            SolanaRequestFixtures.requestCbor(signData, FINGERPRINT, path)

        /** A legacy transfer from account 0 of the wallet, so account 0 is its one signer. */
        fun transfer(): ByteArray = legacyTransfer(SOLANA_KEYS[0])

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
    }
}
