package app.lumio.evm

import app.lensly.app.export.partsOf
import app.lensly.core.evm.EvmFixtures
import app.lensly.core.evm.TxSigner
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.core.ur.EthSignatureEncoder
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/**
 * The send's own rules, without a network: the owner's inputs, the sign request that carries the transaction, and the answer from Lensly.
 * The answer is signed here with the test key of the "abandon ... art" mnemonic, the way Lensly's signer would sign it, so no test holds
 * a key that is not a public test vector.
 */
class EvmSendTest {
    private val sepolia = EvmChain(
        chainId = 11155111L,
        name = "Sepolia",
        symbol = "ETH",
        decimals = 18,
        testnet = true,
        explorerUrl = "https://sepolia.etherscan.io",
        defaultRpc = null,
    )
    private val wallet = HdWallet(Mnemonic.toSeed(EvmFixtures.artMnemonic))
    private val xpub = wallet.accountXpub()
    private val from = AccountAddresses.addressAt(xpub, 0)
    private val key = EvmFixtures.artKey

    private fun sendOf(
        index: Int = 0,
        value: BigInteger = BigInteger.TEN.pow(16),
        balance: BigInteger = BigInteger.TEN.pow(18),
    ) = PreparedSend(
        chain = sepolia,
        index = index,
        from = from,
        to = RECIPIENT,
        valueWei = value,
        nonce = 7,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeePerGas = BigInteger.valueOf(3_500_000_000L),
        maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
        balanceWei = balance,
    )

    /** The message of the refusal that [block] raises. */
    private fun refusal(block: () -> Any?): String = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()

    /** The request that a camera reads from [parts]. */
    private fun readRequest(parts: List<String>): EthSignRequest {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return assertIs<EthSignRequest.Result.Ok>(EthSignRequest.decode(assertNotNull(scanner.result()))).request
    }

    /** The UR that a signature scan completes on [parts], read in order as the camera reads them. */
    private fun scannedSignature(parts: List<String>): UR {
        val scan = SignatureScan()
        val complete = parts.map { scan.onText(it) }.filterIsInstance<SignatureStep.Complete>()
        return assertNotNull(complete.firstOrNull()).ur
    }

    /** The frames of Lensly's answer to [requestId] for [send], signed with [signingKey]. */
    private fun answerParts(send: PreparedSend, requestId: ByteArray, signingKey: ByteArray): List<String> =
        partsOf(EthSignatureEncoder.encode(requestId, TxSigner.sign(send.payload, send.tx, signingKey)))

    @Test
    fun aChecksummedRecipientIsKeptAndTheSpaceAroundItIsTrimmed() {
        assertEquals(RECIPIENT, SendInput.recipient("  $RECIPIENT "))
    }

    @Test
    fun aRecipientWithAWrongChecksumIsRefused() {
        val wrong = RECIPIENT.dropLast(1) + "D"

        assertEquals("The checksum does not match. Check the recipient address.", refusal { SendInput.recipient(wrong) })
    }

    @Test
    fun aRecipientInOneCaseHasNoChecksumAndIsRefused() {
        val lower = RECIPIENT.lowercase()

        assertEquals("This address has no checksum. Enter it with its EIP-55 capital letters.", refusal { SendInput.recipient(lower) })
    }

    @Test
    fun aMalformedRecipientIsRefused() {
        for (bad in listOf("", "0x1234", RECIPIENT.drop(2), RECIPIENT + "0", "0x" + "zz".repeat(20))) {
            assertEquals("Enter the recipient: 0x and 40 hex characters.", refusal { SendInput.recipient(bad) }, "recipient '$bad'")
        }
    }

    @Test
    fun anAmountIsReadInTheChainsUnits() {
        assertEquals(BigInteger.TEN.pow(16), SendInput.amountWei("0.01", 18, "ETH"))
        assertEquals(BigInteger.TEN.pow(18), SendInput.amountWei("1", 18, "ETH"))
        assertEquals(BigInteger.ONE, SendInput.amountWei("0.000000000000000001", 18, "ETH"))
        assertEquals(BigInteger.TEN.pow(18), SendInput.amountWei("1.0000000000000000000", 18, "ETH"))
    }

    @Test
    fun anAmountThatIsNotAPositiveDecimalInTheChainsUnitsIsRefused() {
        assertEquals("Use at most 18 decimal places for ETH.", refusal { SendInput.amountWei("0.0000000000000000001", 18, "ETH") })
        assertEquals("The amount must be more than zero.", refusal { SendInput.amountWei("0", 18, "ETH") })
        for (bad in listOf("", "-1", "1e3", "1.", ".5", "abc", "1,5")) {
            assertEquals("Enter an amount in ETH, such as 0.01.", refusal { SendInput.amountWei(bad, 18, "ETH") }, "amount '$bad'")
        }
    }

    @Test
    fun theSignRequestNamesTheAccountPathTheWalletAndTheTransaction() {
        val send = sendOf(index = 2)
        val requestId = ByteArray(16) { 0x22 }
        val fingerprint = wallet.masterFingerprint.toLong() and 0xFFFFFFFFL

        val parsed = readRequest(partsOf(EthSignRequestEncoder.encode(EvmSend.request(send, fingerprint, requestId))))

        assertContentEquals(requestId, parsed.requestId)
        assertContentEquals(send.payload, parsed.signData)
        assertEquals(DataType.TYPED_TRANSACTION, parsed.dataType)
        assertEquals(11155111L, parsed.chainId)
        assertEquals(
            listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(2, false)),
            parsed.derivationPath,
        )
        assertEquals(wallet.masterFingerprint, parsed.sourceFingerprint)
        assertContentEquals(Numeric.hexStringToByteArray(from), parsed.address)
        assertEquals(EvmSend.ORIGIN, parsed.origin)
    }

    @Test
    fun theRequestCarriesTheEip1559PayloadThatTheWalletSigns() {
        val send = sendOf()

        assertEquals(0x02, send.payload[0].toInt() and 0xFF)
        assertContentEquals(send.tx.encode(), send.payload)
        assertEquals(send.payload.toHex(), send.tx.encode()?.toHex())
    }

    @Test
    fun theUnsignedPayloadOfAPublishedEip1559TransactionIsItsRlp() {
        // The first published EIP-1559 vector (ethereumjs-monorepo, chain ID 4, nonce 819). Its nine unsigned fields are the first
        // nine of its signed list, so the payload is 0x02 and the RLP list of those fields: 43 bytes, which is 0xeb as a list header.
        val signedTx = "02f86e048203338301284d8301d97c828ae094000000000000000000000000000000000000aaaa8402933bc980c080a00f924cb68412c8f1cfd74d9b581c71eeaf94fff6abdde3e5b02ca6b2931dcf47a07dd1c50027c3e31f8b565e25ce68a5072110f61fce5eee81b195dd51273c2f83"
        val fields = signedTx.removePrefix("02f86e").substring(0, 86)
        val send = PreparedSend(
            chain = sepolia.copy(chainId = 4L),
            index = 0,
            from = from,
            to = Keys.toChecksumAddress("0x" + "00".repeat(18) + "aaaa"),
            valueWei = BigInteger.valueOf(43_203_529L),
            nonce = 819,
            gasLimit = BigInteger.valueOf(35_552L),
            maxFeePerGas = BigInteger.valueOf(121_212L),
            maxPriorityFeePerGas = BigInteger.valueOf(75_853L),
            balanceWei = BigInteger.TEN.pow(18),
        )

        assertEquals("02eb$fields", send.payload.toHex())
    }

    @Test
    fun aSignatureFromTheSendingAccountBecomesTheSignedTransaction() {
        val send = sendOf()
        val requestId = ByteArray(16) { 0x33 }
        val signature = TxSigner.sign(send.payload, send.tx, key)

        val raw = EvmSend.signed(send, requestId, scannedSignature(answerParts(send, requestId, key)))

        assertEquals(0x02, raw[0].toInt() and 0xFF)
        assertContentEquals(assertNotNull(send.tx.encodeSigned(signature)), raw)
        assertTrue(raw.size > send.payload.size)
    }

    @Test
    fun aSignatureForAnotherRequestIsRefused() {
        val send = sendOf()
        val ur = scannedSignature(answerParts(send, ByteArray(16) { 0x44 }, key))

        assertEquals("This signature answers a different request.", refusal { EvmSend.signed(send, ByteArray(16) { 0x33 }, ur) })
    }

    @Test
    fun aSignatureFromAnotherAccountIsRefused() {
        val send = sendOf()
        val requestId = ByteArray(16) { 0x55 }
        val otherKey = HdWallet(Mnemonic.toSeed(List(23) { "zoo" } + "vote")).privateKey(0)
        val ur = scannedSignature(answerParts(send, requestId, otherKey))

        assertEquals("This signature is from a different account.", refusal { EvmSend.signed(send, requestId, ur) })
    }

    @Test
    fun aCodeThatIsNotASignatureFromLenslyIsRefused() {
        val send = sendOf()
        val requestParts = partsOf(EthSignRequestEncoder.encode(EvmSend.request(send, 1L, ByteArray(16))))
        val scanner = UrScanner()
        requestParts.forEach { scanner.receive(it) }

        assertEquals("This code is not a signature from Lensly.", refusal { EvmSend.signed(send, ByteArray(16), assertNotNull(scanner.result())) })
    }

    @Test
    fun aSignatureWithARecoveryIdThatIsNotForATypedTransactionIsRefused() {
        val send = sendOf()
        val requestId = ByteArray(16) { 0x66 }
        val signature = TxSigner.sign(send.payload, send.tx, key)
        val legacyStyle = EthSignatureEncoder.encode(requestId, signature.copyOf(64) + byteArrayOf(27))

        assertEquals(
            "This signature is not one for an EIP-1559 transaction.",
            refusal { EvmSend.signed(send, requestId, scannedSignature(partsOf(legacyStyle))) },
        )
    }

    @Test
    fun theExplorerLinkIsTheTransactionPageOfAChainThatHasOne() {
        assertEquals("https://sepolia.etherscan.io/tx/0xabc", EvmSend.explorerLink(sepolia, "0xabc"))
        assertEquals(null, EvmSend.explorerLink(sepolia.copy(explorerUrl = null), "0xabc"))
    }

    @Test
    fun theDerivationPathIsTheAccountPathOfTheIndex() {
        assertEquals(
            listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(4, false)),
            EvmSend.derivationPath(4),
        )
    }

    private companion object {
        /** The EIP-55 example address of the standard. Its checksum is valid. */
        const val RECIPIENT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    }
}
