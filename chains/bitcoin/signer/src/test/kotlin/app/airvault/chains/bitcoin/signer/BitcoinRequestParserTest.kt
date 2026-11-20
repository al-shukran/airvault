package app.airvault.chains.bitcoin.signer

import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainRegistry
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.PathStep
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.KeyPathWithMaster
import fr.acinq.bitcoin.psbt.Psbt
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The master fingerprint that the PSBTs of these tests claim. */
private const val FINGERPRINT = 0xD0E1F2A3L

class BitcoinRequestParserTest {
    private val bytes = Psbt.write(
        Psbt(
            Transaction(
                version = 2,
                txIn = emptyList(),
                txOut = listOf(TxOut(Satoshi(1_000L), ByteVector.empty)),
                lockTime = 0L,
            ),
        ),
    ).toByteArray()
    private val ur = CryptoPSBT(bytes).toUR()

    private fun parse(
        urType: String = ur.type,
        cbor: ByteArray = ur.cborBytes,
        limits: ParserLimits = ParserLimits(),
    ) = BitcoinRequestParser.parse(urType, cbor, limits)

    @Test
    fun aPsbtParsesToARequestWhoseBodyIsThePsbt() {
        val request = assertIs<ParseOutcome.Parsed>(parse()).request
        assertEquals(bitcoinManifest.id, request.header.app)
        assertEquals(ChainId("bip122:000000000019d6689c085ae165831e93"), request.header.chain)
        assertEquals(RequestKind.TRANSACTION, request.header.kind)
        assertContentEquals(bytes, request.body)
    }

    @Test
    fun aUrOfAnotherTypeIsRefused() {
        assertIs<ParseOutcome.Refused>(parse(urType = "algo-sign-request"))
    }

    @Test
    fun aPayloadThatIsNotAPsbtIsRefusedWithTheReason() {
        assertEquals(ParseOutcome.Refused(NOT_A_PSBT), parse(cbor = byteArrayOf(1, 2, 3)))
    }

    @Test
    fun aPsbtLargerThanTheLimitIsRefused() {
        assertIs<ParseOutcome.Refused>(parse(limits = ParserLimits(maxSignDataBytes = bytes.size - 1)))
    }

    @Test
    fun aPayloadLargerThanItsLimitIsRefusedBeforeItIsRead() {
        val limits = ParserLimits(maxPayloadBytes = 4)
        assertEquals(ParseOutcome.Refused("payload exceeds 4 bytes"), parse(cbor = ByteArray(8), limits = limits))
    }

    @Test
    fun aPsbtSpendingATestnet4OutputNamesTestnet4InItsHeader() {
        val request = assertIs<ParseOutcome.Parsed>(parseOf(psbtDerivedUnder(coinType = 1L))).request
        assertEquals(ChainId("bip122:00000000da84f2bafbbc53dee25a72ae"), request.header.chain)
    }

    @Test
    fun aPsbtSpendingAMainnetOutputNamesMainnetInItsHeader() {
        val request = assertIs<ParseOutcome.Parsed>(parseOf(psbtDerivedUnder(coinType = 0L))).request
        assertEquals(ChainId("bip122:000000000019d6689c085ae165831e93"), request.header.chain)
    }

    /** A PSBT with one input, derived under BIP44-style path m/84'/[coinType]'/0' for the generator point's key. */
    private fun psbtDerivedUnder(coinType: Long): Psbt {
        val outPoint = OutPoint(TxHash(ByteVector32(ByteArray(32) { 1 })), 0L)
        val output = TxOut(Satoshi(1_000L), ByteVector.empty)
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(outPoint, ByteVector.empty, 0xFFFFFFFDL)),
            txOut = listOf(output),
            lockTime = 0L,
        )
        val hardened = 0x80000000L
        val path = KeyPath(listOf(hardened + 84L, hardened + coinType, hardened))
        val key = PublicKey(hexBytes("0279BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"))
        val derivation = mapOf(key to KeyPathWithMaster(0xD0E1F2A3L, path))
        return checkNotNull(Psbt(tx).updateWitnessInput(outPoint, output, derivationPaths = derivation).right)
    }

    @Test
    fun theHeaderNamesEachAccountTheInputsClaimOnceInInputOrder() {
        val psbt = psbtClaiming(listOf(keyPath(0, 1), keyPath(0, 2), keyPath(1, 0)))
        val request = assertIs<ParseOutcome.Parsed>(parseOf(psbt)).request
        assertEquals(listOf(accountRef(0), accountRef(1)), request.header.signers)
    }

    @Test
    fun aPsbtWithNoDerivationNamesNoAccount() {
        assertEquals(emptyList(), assertIs<ParseOutcome.Parsed>(parse()).request.header.signers)
    }

    @Test
    fun sixteenAccountsParseAndSeventeenAreRefusedBecauseTheWireCarriesSixteen() {
        val sixteen = psbtClaiming((0L until 16L).map { keyPath(it, 0L) })
        assertEquals(16, assertIs<ParseOutcome.Parsed>(parseOf(sixteen)).request.header.signers.size)
        val seventeen = psbtClaiming((0L until 17L).map { keyPath(it, 0L) })
        assertEquals(ParseOutcome.Refused("The PSBT claims more than 16 Bitcoin accounts."), parseOf(seventeen))
    }

    /** The BIP84 key path m/84'/0'/[account]'/0/[index] on mainnet, under the fingerprint of [FINGERPRINT]. */
    private fun keyPath(account: Long, index: Long): KeyPath =
        KeyPath(listOf(0x8000_0000L + 84L, 0x8000_0000L, 0x8000_0000L + account, 0L, index))

    /** The account reference the header names for account [account] of BIP84 on mainnet. */
    private fun accountRef(account: Int): SignerRef = SignerRef(
        FINGERPRINT.toInt(),
        listOf(PathStep(84, hardened = true), PathStep(0, hardened = true), PathStep(account, hardened = true)),
    )

    /**
     * A PSBT with one input per path in [paths]. Each input spends its own output, and its derivation names that path under the key of the
     * generator point and [FINGERPRINT].
     */
    private fun psbtClaiming(paths: List<KeyPath>): Psbt {
        val key = PublicKey(hexBytes("0279BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798"))
        val output = TxOut(Satoshi(1_000L), ByteVector.empty)
        val outPoints = paths.indices.map { i -> OutPoint(TxHash(ByteVector32(ByteArray(32) { (i + 1).toByte() })), 0L) }
        val tx = Transaction(
            version = 2,
            txIn = outPoints.map { TxIn(it, ByteVector.empty, 0xFFFFFFFDL) },
            txOut = listOf(output),
            lockTime = 0L,
        )
        return paths.foldIndexed(Psbt(tx)) { index, psbt, path ->
            val derivation = mapOf(key to KeyPathWithMaster(FINGERPRINT, path))
            checkNotNull(psbt.updateWitnessInput(outPoints[index], output, derivationPaths = derivation).right)
        }
    }

    private fun parseOf(psbt: Psbt): ParseOutcome {
        val encoded = CryptoPSBT(Psbt.write(psbt).toByteArray()).toUR()
        return parse(encoded.type, encoded.cborBytes)
    }

    private fun hexBytes(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun theRegistryRoutesCryptoPsbtToTheBitcoinApp() {
        val signer = BitcoinSigner()
        assertEquals(signer, ChainRegistry(listOf(signer)).byRequestType(BtcPsbt.UR_TYPE))
    }
}
