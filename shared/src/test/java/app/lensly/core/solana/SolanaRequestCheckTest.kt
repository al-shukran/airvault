package app.lensly.core.solana

import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SolanaRequestCheckTest {

    @Test
    fun theAccountPathIsExactlyPurposeCoinAndHardenedAccount() {
        assertEquals(0, SolanaRequestCheck.accountIndexOf(path(44, 501, 0)))
        assertEquals(7, SolanaRequestCheck.accountIndexOf(path(44, 501, 7)))
        assertNull(SolanaRequestCheck.accountIndexOf(listOf(PathStep(44, true), PathStep(501, true))))
        assertNull(SolanaRequestCheck.accountIndexOf(listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true), PathStep(0, true))))
        assertNull(SolanaRequestCheck.accountIndexOf(listOf(PathStep(44, true), PathStep(501, true), PathStep(0, false))))
        assertNull(SolanaRequestCheck.accountIndexOf(path(44, 60, 0)))
        assertNull(SolanaRequestCheck.accountIndexOf(path(49, 501, 0)))
    }

    /**
     * (b): a path other than m/44'/501'/N' is refused, whatever the wallet holds, and nothing is reviewed. The codec refuses a path that
     * does not start with 44'/501' before the check runs. The check refuses the other wrong paths.
     */
    @Test
    fun aWrongPathIsRefusedWithoutAReview() {
        val keys = listOf(key(1), key(2))
        val signData = legacyTransfer(listOf(keys[0], key(9), system()))
        val codecRefused = listOf(
            listOf(44L to true, 60L to true, 0L to true),
            listOf(49L to true, 501L to true, 0L to true),
        )
        for (steps in codecRefused) {
            val decoded = SolSignRequest.decode(request(signData, path = steps))
            assertIs<SolSignRequest.Result.Rejected>(decoded, "path $steps")
        }
        val checkRefused = listOf(
            listOf(44L to true, 501L to true, 0L to true, 0L to true),
            listOf(44L to true, 501L to true),
            listOf(44L to true, 501L to true, 1L to true, 0L to true),
        )
        for (steps in checkRefused) {
            assertEquals(SolanaRequestCheck.PATH_REFUSED, refusal(request(signData, path = steps), keys), "path $steps")
        }
    }

    @Test
    fun aNonHardenedStepIsRefusedByTheCodecBeforeTheCheck() {
        val bytes = Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(2L) to Cbor.Bytes(ByteArray(32) { 1 }),
                    Cbor.Uint(3L) to keypath(listOf(44L to true, 501L to true, 0L to false)),
                    Cbor.Uint(6L) to Cbor.Uint(1L),
                ),
            ),
        )
        assertIs<SolSignRequest.Result.Rejected>(SolSignRequest.decode(bytes))
    }

    @Test
    fun anAccountTheWalletDoesNotHaveIsRefused() {
        val request = request(legacyTransfer(listOf(key(1), key(9), system())), index = 1)
        assertEquals(SolanaRequestCheck.UNKNOWN_ACCOUNT, refusal(request, listOf(key(1))))
    }

    @Test
    fun aSignerAddressThatIsNotTheAccountsKeyIsRefused() {
        val signData = legacyTransfer(listOf(key(1), key(9), system()))
        assertEquals(SolanaRequestCheck.DIFFERENT_ADDRESS, refusal(request(signData, address = key(2)), listOf(key(1))))
        assertTrue(review(request(signData, address = key(1)), listOf(key(1))).signer.contentEquals(key(1)))
        assertTrue(review(request(signData, address = null), listOf(key(1))).signer.contentEquals(key(1)))
    }

    @Test
    fun aTransactionThatCannotBeReadIsRefused() {
        assertEquals(SolanaRequestCheck.UNREADABLE_TRANSACTION, refusal(request(byteArrayOf(0x7f, 1, 2)), listOf(key(1))))
    }

    /** WI-24: a transaction that lists an account twice is refused, not shown. */
    @Test
    fun aDuplicateAccountKeyIsRefused() {
        val signData = legacyTransfer(listOf(key(1), key(1), system()))
        assertEquals(SolanaRequestCheck.DUPLICATE_ACCOUNT, refusal(request(signData), listOf(key(1))))
    }

    @Test
    fun aTransactionTheAccountDoesNotSignIsRefused() {
        val signData = legacyTransfer(listOf(key(3), key(1), system()))
        assertEquals(SolanaRequestCheck.NOT_A_SIGNER, refusal(request(signData), listOf(key(1))))
    }

    /** (c): a v0 message that loads an account from an address lookup table shows the lookup warning, with the table and index. */
    @Test
    fun aV0MessageWithALookupTableShowsTheLookupWarning() {
        val bytes = SolanaVectors["v0-sol-transfer-alt"]
        val payer = assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(bytes)).message.accountKeys[0]
        val review = review(request(bytes), listOf(payer))
        assertEquals(listOf(SolanaVectors.TABLE), review.lookups.map { it.tableAddress })
        assertTrue(review.warnings.contains(SolanaWarning.LOOKUP_TABLE))
        assertEquals(1, review.instructions.size)
    }

    @Test
    fun anUnknownProgramShowsTheRawWarning() {
        val bytes = SolanaVectors["legacy-unknown-program"]
        val payer = assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(bytes)).message.accountKeys[0]
        val review = review(request(bytes), listOf(payer))
        assertTrue(review.instructions.any { it is SolanaInstruction.Raw })
        assertTrue(review.warnings.contains(SolanaWarning.UNKNOWN_PROGRAM))
    }

    @Test
    fun aKnownTransferWithNoLookupHasNoWarning() {
        val signData = legacyTransfer(listOf(key(1), key(9), system()))
        assertEquals(emptyList(), review(request(signData), listOf(key(1))).warnings)
    }

    @Test
    fun aMessageToSignIsReviewedAsItsBytes() {
        val text = "hello".toByteArray()
        val review = review(request(text, signType = 2L), listOf(key(1)))
        assertEquals(SolSignType.MESSAGE, review.signType)
        assertContentEquals(text, review.messageBytes)
        assertNull(review.message)
        assertEquals(emptyList(), review.warnings)
    }

    private fun review(request: ByteArray, keys: List<ByteArray>): SolanaRequestReview {
        val parsed = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(request)).request
        return assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(parsed, keys)).review
    }

    private fun refusal(request: ByteArray, keys: List<ByteArray>): String {
        val parsed = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(request)).request
        return assertIs<SolanaRequestCheck.Result.Refused>(SolanaRequestCheck.check(parsed, keys)).reason
    }

    private fun key(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

    private fun system(): ByteArray = fr.acinq.bitcoin.Base58.decode(SolanaVectors.SYSTEM)

    private fun path(vararg indexes: Int): List<PathStep> =
        indexes.mapIndexed { position, index -> PathStep(index, position < 3) }

    /** A legacy message: one signer (the first key) and one System Program transfer of 1000 lamports from key 0 to key 1. */
    private fun legacyTransfer(keys: List<ByteArray>): ByteArray {
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(1_000L).array()
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 1))
        out.write(keys.size)
        keys.forEach { out.write(it) }
        out.write(ByteArray(32) { 7 })
        out.write(byteArrayOf(1, 2, 2, 0, 1, 12))
        out.write(data)
        return out.toByteArray()
    }

    /** A sol-sign-request for [signData] on account [index], or on [path] when it is given. */
    private fun request(
        signData: ByteArray,
        index: Int = 0,
        path: List<Pair<Long, Boolean>>? = null,
        address: ByteArray? = null,
        signType: Long = 1L,
    ): ByteArray {
        val steps = path ?: listOf(44L to true, 501L to true, index.toLong() to true)
        val fields = buildList {
            add(Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(ByteArray(16) { 0x11 })))
            add(Cbor.Uint(2L) to Cbor.Bytes(signData))
            add(Cbor.Uint(3L) to keypath(steps))
            address?.let { add(Cbor.Uint(4L) to Cbor.Bytes(it)) }
            add(Cbor.Uint(5L) to Cbor.Text("solflare"))
            add(Cbor.Uint(6L) to Cbor.Uint(signType))
        }
        return Cbor.encode(Cbor.Dict(fields))
    }

    private fun keypath(steps: List<Pair<Long, Boolean>>): Cbor {
        val components = steps.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }
        return Cbor.Tagged(304, Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(0x12121212L))))
    }
}
