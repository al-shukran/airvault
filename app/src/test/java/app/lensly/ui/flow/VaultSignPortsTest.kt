package app.lensly.ui.flow

import android.content.Context
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.chains.evm.signer.UnsignedTx
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolanaRequestParser
import app.airvault.core.model.Approval
import app.airvault.chains.bitcoin.signer.BitcoinSigner
import app.airvault.chains.bitcoin.signer.BtcRefusal
import app.airvault.chains.bitcoin.signer.SigningSourcePolicy
import app.airvault.chains.bitcoin.signer.bitcoinManifest
import app.airvault.core.signer.KeyScopeViolation
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.app.export.partsOf
import app.lensly.core.evm.TxSigner
import app.lensly.core.parse.ScanReply
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.EthSignatureEncoder
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.WalletKeyAccess
import com.sparrowwallet.hummingbird.UR
import app.airvault.core.model.checkLabels
import app.lensly.app.AppSettings
import app.lensly.app.DecoyVault
import app.lensly.app.SettingsStore
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import fr.acinq.bitcoin.psbt.Psbt
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ParseOutcome
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.core.parse.ParserLimits
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.flow.SolanaRequestFixtures.REQUEST_ID
import app.lensly.ui.flow.SolanaRequestFixtures.legacyTransfer
import app.lensly.ui.flow.SolanaRequestFixtures.signatureOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigInteger
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app's Review Record writer, and the Solana branch of the one sign flow's ports, against a real vault. A store that fails in a way the
 * writer does not name still becomes [ReviewNotSaved], so no unexpected exception reaches the sign flow.
 */
@RunWith(RobolectricTestRunner::class)
class VaultSignPortsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val words = List(23) { "abandon" } + "art"

    /** The words of a second wallet, which holds other keys. */
    private val otherWords = "supply excess unit online visa snow include grief sketch grace rural near leaf finish jewel embark elite make venture enroll vacuum worth over pioneer".split(" ")

    @Test
    fun aStoreThatThrowsAnUnexpectedExceptionIsReportedAsReviewNotSaved() {
        val layout = VaultLayout(tmp.root)
        val session = VaultSession(layout, MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        try {
            val wallet = WalletStore(session).create(
                name = "MAIN",
                deviceGenerated = false,
                words = words,
                passphrase = null,
                nowMs = 1L,
            )
            val vault = Vault(
                VaultLayout(File(tmp.root, "vault")),
                MemoryKeystore(),
                FakeClock(),
                KdfParams(memoryKiB = 8 * 1024, iterations = 1),
            )
            val holder = VaultHolder(vault)
            holder.adopt(session, holder.unlockEpoch())
            val ports = VaultSignPorts(context, holder) { _, _, _ ->
                throw GeneralSecurityException("the store failed")
            }

            assertFailsWith<ReviewNotSaved> {
                runBlocking {
                    ports.recordApp(
                        AppReviewEntry(
                            wallet,
                            ReviewOutcome.REFUSED,
                            evmRequest(byteArrayOf(1, 2, 3), MONAD, dataType = DataType.PERSONAL_MESSAGE),
                            reviewed = null,
                            acknowledged = emptyList(),
                            refusal = "Refused",
                        ),
                    )
                }
            }
        } finally {
            session.close()
        }
    }

    /** A Solana check that throws is reported as a refusal, not as a crash, and nothing is signed. */
    @Test
    fun aSolanaCheckThatThrowsIsReportedAsARefusal() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(
                context,
                holderOf(session),
                checkSolana = { _, _, _, _ -> throw IllegalStateException("the check failed") },
            )
            val request = solanaRequest(legacyTransfer(ByteArray(32) { 1 }), fingerprintOf(ports))

            val prepared = runBlocking { ports.appPrepare(id, request) }

            assertEquals(
                "This Solana request could not be reviewed. Nothing was signed.",
                assertIs<AppPrepared.Refused>(prepared).reason,
            )
        } finally {
            session.close()
        }
    }

    /** A Solana request that names no requestId is refused, since its response must echo one. */
    @Test
    fun aSolanaRequestWithoutARequestIdIsRefused() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(context, holderOf(session))
            val request = solanaRequest(legacyTransfer(SolanaAccount.publicKey(seed(), 0)), fingerprintOf(ports), requestId = null)

            val prepared = runBlocking { ports.appPrepare(id, request) }

            assertEquals("Request has no ID", assertIs<AppPrepared.Refused>(prepared).reason)
        } finally {
            session.close()
        }
    }

    /** A Solana request that names another wallet's fingerprint is refused before any key is read. */
    @Test
    fun aSolanaRequestForAnotherWalletIsRefused() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(context, holderOf(session))
            val request = solanaRequest(legacyTransfer(ByteArray(32) { 1 }), fingerprintOf(ports) xor 1)

            val prepared = runBlocking { ports.appPrepare(id, request) }

            assertEquals("This request is for a different wallet", assertIs<AppPrepared.Refused>(prepared).reason)
        } finally {
            session.close()
        }
    }

    /**
     * The sol-signature of the reviewed account verifies with the key the wallet stores for that account, and with no other. The app checks the
     * stored key before it signs, so the key that signs is the one the wallet holds.
     */
    @Test
    fun aSolanaSignatureVerifiesWithTheStoredKeyOfTheReviewedAccount() {
        val (session, id) = sessionWithWallet()
        try {
            val store = WalletStore(session)
            store.addSolanaAccounts(id, 2, BulkCap.DEFAULT.accounts)
            val ports = VaultSignPorts(context, holderOf(session))
            val message = legacyTransfer(SolanaAccount.publicKey(seed(), 1))
            val ready = reviewed(ports, id, solanaRequest(message, fingerprintOf(ports), index = 1L))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))

            val signature = signatureOf(runBlocking { ports.appSign(ready, approval) })

            assertTrue(Ed25519Signer.verify(store.solanaKeys(id)[1], message, signature))
            assertFalse(Ed25519Signer.verify(store.solanaKeys(id)[0], message, signature))
        } finally {
            session.close()
        }
    }

    /** A reviewed account that the wallet holds no key for is refused, and nothing is signed. */
    @Test
    fun aSolanaSignatureForAnAccountTheWalletDoesNotHaveIsRefused() {
        val (session, id) = sessionWithWallet()
        try {
            WalletStore(session).addSolanaAccounts(id, 1, BulkCap.DEFAULT.accounts)
            val ports = VaultSignPorts(context, holderOf(session))
            val ready = reviewed(ports, id, solanaRequest(legacyTransfer(SolanaAccount.publicKey(seed(), 0)), fingerprintOf(ports)))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))

            assertFailsWith<IllegalArgumentException> {
                runBlocking { ports.appSign(ready.copy(accountIndex = 5), approval) }
            }
        } finally {
            session.close()
        }
    }

    /** The sign prompt takes the App Password of the real vault, and refuses its Duress Password, which opens only the decoy. */
    @Test
    fun theSignPromptInTheRealVaultRejectsTheDuressPassword() {
        val holder = openedHolder(duressVault(), APP)
        val ports = VaultSignPorts(context, holder)

        assertEquals(PasswordCheck.Accepted, runBlocking { ports.checkPassword(APP.toCharArray()) })
        assertEquals(PasswordCheck.Rejected, runBlocking { ports.checkPassword(DURESS.toCharArray()) })
        assertNotNull(holder.openSession(), "the real session stays open")
    }

    /** The sign prompt in a decoy takes the Duress Password, and refuses the real App Password. */
    @Test
    fun theSignPromptInTheDecoyRejectsTheRealAppPassword() {
        val holder = openedHolder(duressVault(), DURESS)
        val ports = VaultSignPorts(context, holder)

        assertEquals(PasswordCheck.Accepted, runBlocking { ports.checkPassword(DURESS.toCharArray()) })
        assertEquals(PasswordCheck.Rejected, runBlocking { ports.checkPassword(APP.toCharArray()) })
        assertNotNull(holder.openSession(), "the decoy session stays open")
    }

    /** A real vault with a Duress Password. The App Password opens the real vault, and the Duress Password its decoy. */
    private fun duressVault(): Vault {
        val vault = Vault(VaultLayout(File(tmp.root, "duress")), MemoryKeystore(), FakeClock(), KdfParams(memoryKiB = 8 * 1024, iterations = 1))
        runBlocking {
            assertEquals(CreateResult.Created, vault.create(APP.toCharArray()))
            val real = (vault.unlock(APP.toCharArray()) as UnlockResult.Unlocked).session
            assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { DecoyVault.populate(it) })
            real.close()
        }
        return vault
    }

    /** A holder with the session that [password] opens in [vault] adopted, as the gate leaves it. */
    private fun openedHolder(vault: Vault, password: String): VaultHolder {
        val session = runBlocking { (vault.unlock(password.toCharArray()) as UnlockResult.Unlocked).session }
        val holder = VaultHolder(vault)
        holder.adopt(session, holder.unlockEpoch())
        return holder
    }

    /** A Bitcoin request is reviewed and signed through the ports, and the frames are the signer's own for the reviewed account. */
    @Test
    fun aBitcoinSignatureThroughThePortsIsTheSignersOwnForTheReviewedAccount() {
        val (session, id) = sessionWithWallet()
        try {
            WalletStore(session).addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, BulkCap.DEFAULT.accounts)
            val ports = VaultSignPorts(context, holderOf(session))
            val account = BtcAccount.fromSeed(seed(), BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
            val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
            val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, paid = 30_000L, change = 60_000L))
            val ready = reviewed(ports, id, bitcoinRequest(psbt, account.masterFingerprint.toInt()))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))

            assertEquals(expectedFrames(ready.request, approval), runBlocking { ports.appSign(ready, approval) })
        } finally {
            session.close()
        }
    }

    /** A request that also claims an input of a key outside the vault names the routed wallet's account alone, and signs only that input. */
    @Test
    fun aBitcoinRequestWithAnOutsideInputSignsOnlyTheAccountOfTheRoutedWallet() {
        val (session, id) = sessionWithWallet()
        try {
            WalletStore(session).addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, BulkCap.DEFAULT.accounts)
            val ours = BtcAccount.fromSeed(seed(), BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
            val outside = BtcAccount.fromSeed(ByteArray(16) { 9 }, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
            val spends = listOf(
                BtcFixtures.Spend(BtcFixtures.funding(ours, 0, 0, 100_000L), ours, 0, 0),
                BtcFixtures.Spend(BtcFixtures.funding(outside, 0, 0, 50_000L), outside, 0, 0),
            )
            val ports = VaultSignPorts(context, holderOf(session))
            val psbt = BtcFixtures.psbt(spends, BtcFixtures.paying(ours, paid = 30_000L, change = 110_000L))
            val fingerprints = listOf(ours.masterFingerprint.toInt(), outside.masterFingerprint.toInt())
            val ready = reviewed(ports, id, bitcoinRequest(psbt, *fingerprints.toIntArray()))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))

            assertEquals(listOf(fingerprintOf(ports)), ready.request.header.signers.map { it.fingerprint })
            assertEquals(expectedFrames(ready.request, approval), runBlocking { ports.appSign(ready, approval) })
        } finally {
            session.close()
        }
    }

    /** Claimed inputs of two vault wallets are one request that no single signature covers, so it is refused as several accounts, for either wallet. */
    @Test
    fun aBitcoinRequestClaimingInputsOfTwoVaultWalletsIsRefusedAsSeveralAccounts() {
        val (session, id) = sessionWithWallet()
        try {
            val store = WalletStore(session)
            val other = store.create(name = "OTHER", deviceGenerated = false, words = otherWords, passphrase = null, nowMs = 2L)
            store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, BulkCap.DEFAULT.accounts)
            store.addBitcoinAccounts(other, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, BulkCap.DEFAULT.accounts)
            val ours = BtcAccount.fromSeed(seed(), BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
            val theirs = BtcAccount.fromSeed(Mnemonic.of(otherWords).use { it.deriveSeed("") }, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
            val spends = listOf(
                BtcFixtures.Spend(BtcFixtures.funding(ours, 0, 0, 100_000L), ours, 0, 0),
                BtcFixtures.Spend(BtcFixtures.funding(theirs, 0, 0, 100_000L), theirs, 0, 0),
            )
            val psbt = BtcFixtures.psbt(spends, BtcFixtures.paying(ours, paid = 30_000L, change = 150_000L))
            val request = bitcoinRequest(psbt, ours.masterFingerprint.toInt(), theirs.masterFingerprint.toInt())
            val ports = VaultSignPorts(context, holderOf(session))

            assertEquals(BtcRefusal.SeveralAccounts.reason, assertIs<AppPrepared.Refused>(runBlocking { ports.appPrepare(id, request) }).reason)
            assertEquals(BtcRefusal.SeveralAccounts.reason, assertIs<AppPrepared.Refused>(runBlocking { ports.appPrepare(other, request) }).reason)
        } finally {
            session.close()
        }
    }

    /** The owner's Bitcoin settings decide the signature: a request reviewed under the default source policy is refused once Companion only is set. */
    @Test
    fun theOwnersBitcoinSourcePolicyDecidesTheSignature() {
        val (session, id) = sessionWithWallet()
        try {
            WalletStore(session).addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, BulkCap.DEFAULT.accounts)
            val account = BtcAccount.fromSeed(seed(), BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0)
            val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
            val ports = VaultSignPorts(context, holderOf(session))
            val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, paid = 30_000L, change = 60_000L))
            val ready = reviewed(ports, id, bitcoinRequest(psbt, account.masterFingerprint.toInt()))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))
            SettingsStore(session).save(AppSettings(bitcoinSigningSource = SigningSourcePolicy.COMPANION_ONLY))

            val refused = assertFailsWith<IllegalArgumentException> { runBlocking { ports.appSign(ready, approval) } }

            assertEquals(BtcRefusal.SourceExcluded.reason, refused.message)
        } finally {
            session.close()
        }
    }

    /**
     * A non-witness input that spends a segwit output is refused through the one sign flow, for each segwit Script Type, before any signature:
     * the legacy digest does not commit to the amount.
     */
    @Test
    fun aNonWitnessInputSpendingASegwitOutputIsRefusedThroughTheFlow() {
        val (session, id) = sessionWithWallet()
        try {
            val types = listOf(ScriptType.NATIVE_SEGWIT, ScriptType.NESTED_SEGWIT, ScriptType.TAPROOT)
            types.forEach { WalletStore(session).addBitcoinAccounts(id, it, BtcNetwork.TESTNET4, 1, BulkCap.DEFAULT.accounts) }
            val ports = VaultSignPorts(context, holderOf(session))
            val walletSeed = seed()
            for (type in types) {
                val account = BtcAccount.fromSeed(walletSeed, BtcNetwork.TESTNET4, type, 0)
                val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
                val psbt = BtcFixtures.previousTransactionOnly(spend, BtcFixtures.paying(account, paid = 90_000L, change = 0L).take(1))
                val flow = SignFlow(scanningAs(ports, bitcoinRequest(psbt, account.masterFingerprint.toInt())), id)

                runBlocking { flow.onScanned("crypto-psbt") }

                assertEquals(SignStep.Refused(BtcRefusal.SegwitWithoutWitness(0).reason), flow.state.value, type.name)
            }
        } finally {
            session.close()
        }
    }

    /**
     * A transaction that account 0 of a Mnemonic wallet sends is reviewed and signed through the one sign flow, with that account's key. The answer
     * is byte for byte the one the sign path gave before the flow moved to the chain app.
     */
    @Test
    fun anEthereumTransactionIsSignedWithTheKeyOfItsAccountAsBefore() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(context, holderOf(session))
            val signData = transfer(MONAD)
            val request = evmRequest(signData, MONAD, fingerprint = fingerprintOf(ports))

            assertEquals(expectedEvmFrames(signData, walletKey(0)), signedFrames(ports, id, request))
        } finally {
            session.close()
        }
    }

    /**
     * A Single Key wallet holds one key. Its review does not read the path a request names, so the request is signed with that key as before, even
     * when the path names another account.
     */
    @Test
    fun aSingleKeyWalletSignsAnEthereumTransactionWithItsOnlyKey() {
        val key = walletKey(3)
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        try {
            val id = WalletStore(session).createSingleKey("KEY", key.copyOf(), nowMs = 1L)
            val ports = VaultSignPorts(context, holderOf(session))
            val signData = transfer(MONAD)
            val request = evmRequest(signData, MONAD, account = 5L, address = Numeric.hexStringToByteArray(AccountAddresses.addressOfPrivateKey(key)))

            assertEquals(expectedEvmFrames(signData, key), signedFrames(ports, id, request))
        } finally {
            session.close()
        }
    }

    /** A transaction on a chain outside the manifest is reviewed with a warning, and signed as well, as the sign flow always signed such a request. */
    @Test
    fun aTransactionOnAChainOutsideTheManifestIsSignedToo() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(context, holderOf(session))
            val signData = transfer(POLYGON)
            val request = evmRequest(signData, POLYGON, fingerprint = fingerprintOf(ports))

            assertEquals(expectedEvmFrames(signData, walletKey(0)), signedFrames(ports, id, request))
        } finally {
            session.close()
        }
    }

    /** A request for a key outside the scope of the EVM app is refused by the key access when it is signed. */
    @Test
    fun aRequestForAKeyOutsideTheEvmScopeIsRefusedWhenItIsSigned() {
        val (session, id) = sessionWithWallet()
        try {
            val ports = VaultSignPorts(context, holderOf(session))
            val fingerprint = fingerprintOf(ports)
            val request = evmRequest(transfer(MONAD), MONAD, fingerprint = fingerprint)
            val ready = reviewed(ports, id, request)
            // The review stays the one the owner saw. Only the path the header names changes, to m/44'/60'/0'/1/0: the EVM scope holds branch 0 only.
            val outsidePath = listOf(PathStep(44, hardened = true), PathStep(60, hardened = true), PathStep(0, hardened = true), PathStep(1, hardened = false), PathStep(0, hardened = false))
            val forged = ready.copy(request = request.copy(header = request.header.copy(signers = listOf(SignerRef(fingerprint, outsidePath)))))
            val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))

            assertFailsWith<KeyScopeViolation> { runBlocking { ports.appSign(forged, approval) } }
        } finally {
            session.close()
        }
    }

    /**
     * The Bitcoin request of [psbt] that names an account of each fingerprint in [fingerprints], at account 0 of native segwit on testnet4: the
     * header the parser makes for those inputs, with the PSBT's own bytes as its body. The parser is not run here, since Robolectric cannot decode
     * the `crypto-psbt` CBOR (hummingbird needs a newer cbor than the Robolectric classpath has).
     */
    private fun bitcoinRequest(psbt: Psbt, vararg fingerprints: Int): AppRequest = AppRequest(
        RequestHeader(
            app = bitcoinManifest.id,
            chain = bitcoinManifest.networks.single { it.testnet }.chainId,
            kind = RequestKind.TRANSACTION,
            signers = fingerprints.map { SignerRef(it, listOf(PathStep(84, hardened = true), PathStep(1, hardened = true), PathStep(0, hardened = true))) },
            requestId = null,
        ),
        Psbt.write(psbt).toByteArray(),
    )

    /** The frames of the response [BitcoinSigner] makes for [request] under [approval], signed with the test wallet's keys. */
    private fun expectedFrames(request: AppRequest, approval: Approval): List<String> {
        val response = BitcoinSigner().sign(request, approval, WalletKeyAccess(seed()))
        return partsOf(framesOf(UR(response.urType, response.cbor), DEFAULT_MAX_FRAGMENT))
    }

    /** The ports of [ports], with the Parser Sandbox's answer to any scan set to [request]. The sandbox decodes the CBOR, which Robolectric cannot. */
    private fun scanningAs(ports: SignFlowPorts, request: AppRequest): SignFlowPorts = object : SignFlowPorts by ports {
        override suspend fun scan(parts: List<String>): ScanReply = ScanReply.App(request)
    }

    /** A vault session holding one wallet of [words], and that wallet's id. The caller closes the session. */
    private fun sessionWithWallet(): Pair<VaultSession, String> {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it + 5).toByte() })
        val id = WalletStore(session).create(name = "MAIN", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
        return session to id
    }

    /** A holder that reads and writes through [session], as the open vault does. */
    private fun holderOf(session: VaultSession): VaultHolder {
        val vault = Vault(
            VaultLayout(File(tmp.root, "vault")),
            MemoryKeystore(),
            FakeClock(),
            KdfParams(memoryKiB = 8 * 1024, iterations = 1),
        )
        val holder = VaultHolder(vault)
        holder.adopt(session, holder.unlockEpoch())
        return holder
    }

    private fun seed(): ByteArray = Mnemonic.of(words).use { it.deriveSeed("") }

    /** The master fingerprint of the one wallet [ports] shows. */
    private fun fingerprintOf(ports: VaultSignPorts): Int = checkNotNull(runBlocking { ports.wallets() }.single().masterFingerprint)

    /** The request a wallet sends for [signData] on account [index] of the wallet with [fingerprint], parsed as the Parser Sandbox parses it. */
    private fun solanaRequest(signData: ByteArray, fingerprint: Int, index: Long = 0L, requestId: ByteArray? = REQUEST_ID): AppRequest {
        val path = listOf(44L to true, 501L to true, index to true)
        val cbor = SolanaRequestFixtures.requestCbor(signData, fingerprint, path, requestId)
        return assertIs<ParseOutcome.Parsed>(SolanaRequestParser.parse(SolSignRequest.UR_TYPE, cbor, ParserLimits())).request
    }

    /** The review of [request] against wallet [id], which must pass every check. */
    private fun reviewed(ports: VaultSignPorts, id: String, request: AppRequest): AppPrepared.Ready =
        assertIs<AppPrepared.Ready>(runBlocking { ports.appPrepare(id, request) })

    /** The frames [ports] answers [request] with, once it is reviewed and every check of its review is ticked. */
    private fun signedFrames(ports: VaultSignPorts, id: String, request: AppRequest): List<String> {
        val ready = reviewed(ports, id, request)
        val approval = assertNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))
        return runBlocking { ports.appSign(ready, approval) }
    }

    /** The frames the sign path gave for the transaction [signData] under [key], before the sign flow moved to the chain app. */
    private fun expectedEvmFrames(signData: ByteArray, key: ByteArray): List<String> {
        val tx = assertIs<UnsignedTx.Result.Ok>(UnsignedTx.decode(signData, DataType.TRANSACTION)).tx
        return partsOf(EthSignatureEncoder.encode(EVM_REQUEST_ID, TxSigner.sign(signData, tx, key)))
    }

    /** The private key of account [index] of the test wallet. */
    private fun walletKey(index: Int): ByteArray = HdWallet(seed()).privateKey(index)

    /** A legacy transfer of one wei on [chainId], encoded as a wallet sends it for signing. */
    private fun transfer(chainId: Long): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            BigInteger.valueOf(3),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(60_000),
            RECIPIENT,
            BigInteger.ONE,
            "0x",
        ),
        chainId,
    )

    /**
     * The eth-sign-request a wallet sends for [signData] on chain [chainId], parsed as the Parser Sandbox parses it. Its path is account [account]
     * m/44'/60'/[account]'/0/0. It names the master [fingerprint] of a Mnemonic wallet, or, for a Single Key, only its [address].
     */
    private fun evmRequest(
        signData: ByteArray,
        chainId: Long,
        fingerprint: Int? = null,
        account: Long = 0L,
        address: ByteArray? = null,
        dataType: DataType = DataType.TRANSACTION,
    ): AppRequest {
        val steps = listOf(44L to true, 60L to true, account to true, 0L to false, 0L to false)
        val keypath = buildList<Pair<Cbor, Cbor>> {
            add(Cbor.Uint(1) to Cbor.Arr(steps.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }))
            fingerprint?.let { add(Cbor.Uint(2) to Cbor.Uint(it.toLong() and 0xFFFF_FFFFL)) }
        }
        val fields = buildList<Pair<Cbor, Cbor>> {
            add(Cbor.Uint(1) to Cbor.Tagged(37, Cbor.Bytes(EVM_REQUEST_ID)))
            add(Cbor.Uint(2) to Cbor.Bytes(signData))
            add(Cbor.Uint(3) to Cbor.Uint(dataType.wire.toLong()))
            add(Cbor.Uint(4) to Cbor.Uint(chainId))
            add(Cbor.Uint(5) to Cbor.Tagged(304, Cbor.Dict(keypath)))
            address?.let { add(Cbor.Uint(6) to Cbor.Bytes(it)) }
        }
        return assertIs<ParseOutcome.Parsed>(EvmRequestParser.parse(EthSignRequest.UR_TYPE, Cbor.encode(Cbor.Dict(fields)), ParserLimits())).request
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        const val MONAD = 10143L
        const val POLYGON = 137L
        const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        val EVM_REQUEST_ID = ByteArray(16) { 0x33 }
    }
}
