package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaInstruction
import app.airvault.chains.solana.signer.SolanaMessage
import app.airvault.chains.solana.signer.SolanaOffchainMessage
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.airvault.chains.solana.signer.SolanaRequestReview
import app.airvault.chains.solana.signer.SolanaWarning
import app.lensly.core.registry.SolanaFees
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    /**
     * R04: a SOL transfer whose recipient is loaded from an address lookup table is refused. Lensly cannot check that address offline, so
     * it does not sign a transfer to it.
     */
    @Test
    fun aTransferToALookupTableAccountIsRefused() {
        val bytes = SolanaVectors["v0-sol-transfer-alt"]
        val payer = assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(bytes)).message.accountKeys[0]
        assertEquals(SolanaRequestCheck.LOOKUP_RECIPIENT_REFUSAL, refusal(request(bytes), listOf(payer)))
    }

    /**
     * R03: a price with no limit pays for the default units. A System Program transfer is a builtin, so it is given 3,000 units, not the
     * 200,000 of a program that is not a builtin. The compute price instruction is a builtin too and adds 3,000. At one lamport per unit here,
     * the fee is 6,000 lamports.
     */
    @Test
    fun aPriceWithNoLimitHasAPriorityFeeAtTheDefaultUnits() {
        val signData = legacyMessage(
            keys = listOf(key(1), key(9), system(), computeBudget()),
            instructions = listOf(computePrice(1_000_000L), transfer(from = 0, to = 1)),
            readonlyUnsigned = 2,
        )
        val review = review(request(signData), listOf(key(1)))
        assertNull(review.computeUnitLimit)
        assertEquals(6_000uL, review.priorityFeeLamports)
        assertTrue(review.priorityFeeIsBound)
    }

    /**
     * R03: a nonce advance and a transfer are two System Program instructions, so they are two builtin defaults, and the compute price
     * instruction a third: 9,000 units. The nonce advance comes first, as a durable nonce requires.
     */
    @Test
    fun aNonceAdvanceAndATransferPayForTwoBuiltinDefaults() {
        val recentBlockhashes = fr.acinq.bitcoin.Base58.decode(RECENT_BLOCKHASHES)
        val signData = legacyMessage(
            keys = listOf(key(1), key(9), key(2), computeBudget(), system(), recentBlockhashes),
            instructions = listOf(
                // AdvanceNonceAccount: accounts [nonce, RecentBlockhashes sysvar, authority]; data u32 LE 4.
                Compiled(program = 4, accounts = listOf(2, 5, 0), data = le32(4)),
                computePrice(1_000_000L),
                transfer(from = 0, to = 1, program = 4),
            ),
            readonlyUnsigned = 3,
        )
        val review = review(request(signData), listOf(key(1)))
        assertNull(review.computeUnitLimit)
        assertEquals(9_000uL, review.priorityFeeLamports)
    }

    /** R03: an SPL TransferChecked is not a builtin, so it pays for the full 200,000 units by default, and the compute price instruction adds 3,000. */
    @Test
    fun aTokenTransferPaysForTheFullDefaultUnits() {
        val review = review(request(tokenTransfers(count = 1)), listOf(key(1)))
        assertNull(review.computeUnitLimit)
        assertEquals(203_000uL, review.priorityFeeLamports)
    }

    /** R03: the default units are capped at the 1,400,000 a transaction can request: eight token transfers would be 1,600,000. */
    @Test
    fun theDefaultUnitsAreCappedAtTheMaximumLimit() {
        val review = review(request(tokenTransfers(count = 8)), listOf(key(1)))
        assertEquals(1_400_000uL, review.priorityFeeLamports)
    }

    /** R03: a set limit sets the units the priority fee pays for, so the fee is exact. */
    @Test
    fun aSetLimitSetsTheUnitsOfThePriorityFee() {
        val signData = legacyMessage(
            keys = listOf(key(1), key(9), system(), computeBudget()),
            instructions = listOf(computeLimit(100_000u), computePrice(1_000_000L), transfer(from = 0, to = 1)),
            readonlyUnsigned = 2,
        )
        val review = review(request(signData), listOf(key(1)))
        assertEquals(100_000u, review.computeUnitLimit)
        assertEquals(100_000uL, review.priorityFeeLamports)
        assertFalse(review.priorityFeeIsBound)
    }

    /** The fee constants come from the [SolanaFees] the check is given, so a signed package can correct them: here every constant differs from the default. */
    @Test
    fun theFeeIsComputedFromTheGivenConstants() {
        val fees = SolanaFees(lamportsPerSignature = 7_000, defaultUnitsPerInstruction = 100_000, builtinUnitsPerInstruction = 1_000, maxUnits = 150_000)
        val transfer = legacyMessage(
            keys = listOf(key(1), key(9), system(), computeBudget()),
            instructions = listOf(computePrice(1_000_000L), transfer(from = 0, to = 1)),
            readonlyUnsigned = 2,
        )

        val review = review(request(transfer), listOf(key(1)), fees = fees)

        assertEquals(7_000uL, review.baseFeeLamports)
        // Two builtin instructions at 1,000 units, at one lamport per unit.
        assertEquals(2_000uL, review.priorityFeeLamports)
        assertEquals(9_000uL, review.maxFeeLamports)
        // Two token transfers at 100,000 units and the price instruction at 1,000 are 201,000 units, capped at 150,000.
        assertEquals(150_000uL, review(request(tokenTransfers(count = 2)), listOf(key(1)), fees = fees).priorityFeeLamports)
    }

    /** Without constants the review uses the ones this build ships: 5,000 lamports a signature. */
    @Test
    fun theFeeUsesTheBuiltInConstantsWhenNoneAreGiven() {
        val review = review(request(tokenTransfers(count = 1)), listOf(key(1)))

        assertEquals(5_000uL, review.baseFeeLamports)
        assertEquals(SolanaFees.DEFAULT, review.fees)
    }

    /** R03: a transaction that sets its compute limit twice is refused, since its fee cannot be read from one limit. */
    @Test
    fun aTransactionThatSetsItsComputeLimitTwiceIsRefused() {
        val signData = legacyMessage(
            keys = listOf(key(1), key(9), system(), computeBudget()),
            instructions = listOf(computeLimit(100_000u), computeLimit(200_000u), transfer(from = 0, to = 1)),
            readonlyUnsigned = 2,
        )
        assertEquals(SolanaRequestCheck.DUPLICATE_COMPUTE_BUDGET, refusal(request(signData), listOf(key(1))))
    }

    /**
     * The network applies a compute budget instruction by its tag, whatever its accounts or extra bytes, so one that Lensly cannot read
     * exactly would charge a fee the review does not show. Each such request is refused: a price with a trailing byte, a price with an account,
     * and a limit with a trailing byte beside a price that is read.
     */
    @Test
    fun aComputeBudgetInstructionThatIsNotReadExactlyIsRefused() {
        val price = 1_000_000_000_000L
        val cases = mapOf(
            "price with a trailing byte" to listOf(Compiled(3, emptyList(), byteArrayOf(3) + le64(price) + byteArrayOf(0))),
            "price with an account" to listOf(Compiled(3, listOf(1), byteArrayOf(3) + le64(price))),
            "limit with a trailing byte beside a read price" to listOf(
                Compiled(3, emptyList(), byteArrayOf(2) + le32(1_400_000) + byteArrayOf(0)),
                computePrice(price),
            ),
            "limit with an account" to listOf(Compiled(3, listOf(1), byteArrayOf(2) + le32(1_400_000))),
            "an instruction of another compute budget tag" to listOf(Compiled(3, emptyList(), byteArrayOf(1) + le32(65_536))),
        )
        for ((name, budget) in cases) {
            val signData = legacyMessage(
                keys = listOf(key(1), key(9), system(), computeBudget()),
                instructions = budget + transfer(from = 0, to = 1),
                readonlyUnsigned = 2,
            )
            for (policy in UnknownCallPolicy.entries) {
                assertEquals(SolanaRequestCheck.UNREAD_COMPUTE_BUDGET, refusal(request(signData), listOf(key(1)), policy), "$name, $policy")
            }
        }
    }

    /** R06: a durable-nonce advance warns that the transaction has no expiry, which the review shows as information. */
    @Test
    fun aDurableNonceAdvanceWarnsThatItHasNoExpiry() {
        val recentBlockhashes = fr.acinq.bitcoin.Base58.decode(RECENT_BLOCKHASHES)
        val signData = legacyMessage(
            keys = listOf(key(1), key(9), key(2), system(), recentBlockhashes),
            instructions = listOf(
                // AdvanceNonceAccount: accounts [nonce, RecentBlockhashes sysvar, authority]; data u32 LE 4.
                Compiled(program = 3, accounts = listOf(2, 4, 0), data = le32(4)),
                transfer(from = 0, to = 1, program = 3),
            ),
            readonlyUnsigned = 2,
        )
        assertEquals(listOf(SolanaWarning.DURABLE_NONCE), review(request(signData), listOf(key(1))).warnings)
    }

    @Test
    fun anUnknownProgramShowsTheRawWarning() {
        val bytes = SolanaVectors["legacy-unknown-program"]
        val payer = assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(bytes)).message.accountKeys[0]
        val review = review(request(bytes), listOf(payer))
        assertTrue(review.instructions.any { it is SolanaInstruction.Raw })
        assertTrue(review.warnings.contains(SolanaWarning.UNKNOWN_PROGRAM))
    }

    /** Under Warn an unknown program is reviewed under its warning; under Refuse the same transaction is refused. */
    @Test
    fun anUnknownProgramIsReviewedUnderWarnAndRefusedUnderRefuse() {
        val bytes = SolanaVectors["legacy-unknown-program"]
        val payer = assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(bytes)).message.accountKeys[0]
        assertTrue(review(request(bytes), listOf(payer), UnknownCallPolicy.WARN).warnings.contains(SolanaWarning.UNKNOWN_PROGRAM))
        assertEquals(SolanaRequestCheck.UNKNOWN_PROGRAM_REFUSAL, refusal(request(bytes), listOf(payer), UnknownCallPolicy.REFUSE))
    }

    /** Under Refuse a transaction that calls only known programs is still reviewed, with no warning. */
    @Test
    fun aKnownTransferIsReviewedUnderRefuse() {
        val signData = legacyTransfer(listOf(key(1), key(9), system()))
        assertEquals(emptyList(), review(request(signData), listOf(key(1)), UnknownCallPolicy.REFUSE).warnings)
    }

    /** The signer rule still decides first: a transaction the account does not sign is refused as before, under either policy. */
    @Test
    fun aTransactionThatDoesNotSignIsStillRefusedUnderEitherPolicy() {
        val bytes = SolanaVectors["legacy-unknown-program"]
        for (policy in UnknownCallPolicy.entries) {
            assertEquals(SolanaRequestCheck.NOT_A_SIGNER, refusal(request(bytes), listOf(key(1)), policy), "policy $policy")
        }
    }

    @Test
    fun aKnownTransferWithNoLookupHasNoWarning() {
        val signData = legacyTransfer(listOf(key(1), key(9), system()))
        assertEquals(emptyList(), review(request(signData), listOf(key(1))).warnings)
    }

    /** An off-chain message that names the account is reviewed as its text and its application domain. */
    @Test
    fun aMessageToSignIsReviewedAsItsText() {
        val envelope = offchainEnvelope("hello".toByteArray(), domain = key(5), signers = listOf(key(9), key(1)))
        val review = review(request(envelope, signType = 2L), listOf(key(1)))
        assertEquals(SolSignType.MESSAGE, review.signType)
        val message = assertNotNull(review.offchainMessage)
        assertEquals("hello", message.text)
        assertContentEquals(key(5), message.applicationDomain)
        assertNull(review.message)
        assertEquals(emptyList(), review.warnings)
    }

    /** A transfer that decodes as a transaction is refused when it is sent as a message, so no message signature is a transaction's. */
    @Test
    fun aTransferSentAsAMessageIsRefusedAsATransaction() {
        val signData = legacyTransfer(listOf(key(1), key(9), system()))
        assertEquals(SolanaRequestCheck.TRANSACTION_AS_MESSAGE, refusal(request(signData, signType = 2L), listOf(key(1))))
    }

    @Test
    fun headerlessBytesSentAsAMessageAreRefused() {
        assertEquals(SolanaOffchainMessage.NOT_OFF_CHAIN, refusal(request(byteArrayOf(1, 2, 3), signType = 2L), listOf(key(1))))
    }

    @Test
    fun aMessageThatDoesNotNameTheAccountAsASignerIsRefused() {
        val envelope = offchainEnvelope("hello".toByteArray(), signers = listOf(key(9)))
        assertEquals(SolanaRequestCheck.SIGNER_NOT_NAMED, refusal(request(envelope, signType = 2L), listOf(key(1))))
    }

    @Test
    fun aMessageInExtendedUtf8IsRefused() {
        val envelope = offchainEnvelope("hello".toByteArray(), format = 2)
        assertEquals(SolanaOffchainMessage.UNSUPPORTED_FORMAT, refusal(request(envelope, signType = 2L), listOf(key(1))))
    }

    /** A length field that names more bytes than the message holds is refused, with no crash. */
    @Test
    fun aMessageWhoseLengthOverrunsItsBytesIsRefused() {
        val envelope = offchainEnvelope("hello".toByteArray(), declaredLength = 500)
        assertEquals(SolanaOffchainMessage.LENGTH_MISMATCH, refusal(request(envelope, signType = 2L), listOf(key(1))))
    }

    /** A line break is part of a text message, so the review keeps the whole text. */
    @Test
    fun aMessageWithALineBreakIsReviewedAsItsWholeText() {
        val envelope = offchainEnvelope("Sign in\nNonce 42".toByteArray(), format = 1)
        val review = review(request(envelope, signType = 2L), listOf(key(1)))
        assertEquals("Sign in\nNonce 42", assertNotNull(review.offchainMessage).text)
    }

    private fun review(
        request: ByteArray,
        keys: List<ByteArray>,
        unknownCalls: UnknownCallPolicy = UnknownCallPolicy.WARN,
        fees: SolanaFees = SolanaFees.DEFAULT,
    ): SolanaRequestReview {
        val parsed = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(request)).request
        return assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(parsed, keys, unknownCalls = unknownCalls, fees = fees)).review
    }

    private fun refusal(
        request: ByteArray,
        keys: List<ByteArray>,
        unknownCalls: UnknownCallPolicy = UnknownCallPolicy.WARN,
    ): String {
        val parsed = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(request)).request
        return assertIs<SolanaRequestCheck.Result.Refused>(SolanaRequestCheck.check(parsed, keys, unknownCalls = unknownCalls)).reason
    }

    private fun key(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

    private fun system(): ByteArray = fr.acinq.bitcoin.Base58.decode(SolanaVectors.SYSTEM)

    private fun computeBudget(): ByteArray = fr.acinq.bitcoin.Base58.decode(COMPUTE_BUDGET)

    /**
     * A legacy message with a price of 1,000,000 micro-lamports per unit and [count] SPL TransferChecked instructions of 2,000,000 units
     * each from key 2 to key 1 through the mint at key 4. The token program is key 5, the compute budget program key 3.
     */
    private fun tokenTransfers(count: Int): ByteArray {
        val tokenProgram = fr.acinq.bitcoin.Base58.decode(SolanaVectors.TOKEN)
        val transferChecked = Compiled(
            program = 5,
            accounts = listOf(2, 4, 1, 0),
            data = byteArrayOf(12) + le64(2_000_000L) + byteArrayOf(6),
        )
        return legacyMessage(
            keys = listOf(key(1), key(9), key(2), computeBudget(), key(3), tokenProgram),
            instructions = listOf(computePrice(1_000_000L)) + List(count) { transferChecked },
            readonlyUnsigned = 3,
        )
    }

    /** A compiled instruction: its program and accounts as indices into the keys, and its data. */
    private class Compiled(val program: Int, val accounts: List<Int>, val data: ByteArray)

    /** A System Program transfer of 1000 lamports from key index [from] to key index [to]. The System Program is key index [program]. */
    private fun transfer(from: Int, to: Int, program: Int = 2) =
        Compiled(program = program, accounts = listOf(from, to), data = le32(2) + le64(1_000L))

    /** A SetComputeUnitLimit to [units]: tag 2, then a u32. The program is key index 3, the compute budget program. */
    private fun computeLimit(units: UInt) = Compiled(program = 3, accounts = emptyList(), data = byteArrayOf(2) + le32(units.toInt()))

    /** A SetComputeUnitPrice of [microLamports] per unit: tag 3, then a u64. The program is key index 3, the compute budget program. */
    private fun computePrice(microLamports: Long) =
        Compiled(program = 3, accounts = emptyList(), data = byteArrayOf(3) + le64(microLamports))

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /**
     * A legacy message with one signer, the first key, and [readonlyUnsigned] read-only unsigned keys at the end of [keys]. Key 2 is the
     * System Program in every test that uses [transfer].
     */
    private fun legacyMessage(keys: List<ByteArray>, instructions: List<Compiled>, readonlyUnsigned: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, readonlyUnsigned.toByte()))
        out.write(keys.size)
        keys.forEach { out.write(it) }
        out.write(ByteArray(32) { 7 })
        out.write(instructions.size)
        instructions.forEach { instruction ->
            out.write(instruction.program)
            out.write(instruction.accounts.size)
            out.write(instruction.accounts.map { it.toByte() }.toByteArray())
            out.write(instruction.data.size)
            out.write(instruction.data)
        }
        return out.toByteArray()
    }

    private companion object {
        const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
        const val RECENT_BLOCKHASHES = "SysvarRecentB1ockHashes11111111111111111111"
    }

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
