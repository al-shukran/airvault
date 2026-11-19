package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaMessageAccount
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import fr.acinq.bitcoin.Base58
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The durable-nonce shapes: AdvanceNonceAccount as the first instruction, and the one-time setup of CreateAccount or
 * CreateAccountWithSeed, then InitializeNonceAccount. Each message is built byte by byte, and each test cites the layout it uses.
 */
class SolanaNonceTest {

    /** Key 0 signs and pays. Keys 1 to 4 are the nonce, the recipient, the System Program and the RecentBlockhashes sysvar. */
    @Test
    fun aNonceAdvanceFirstIsKnownAndAddsNoUnknownProgramWarning() {
        val review = review(
            legacy(
                keys = listOf(PAYER, NONCE, RECIPIENT, SYSTEM, RECENT_BLOCKHASHES),
                readonly = 2,
                instructions = listOf(
                    // AdvanceNonceAccount: accounts [nonce, RecentBlockhashes sysvar, authority]; data u32 LE 4.
                    Ix(program = 3, accounts = listOf(1, 4, 0), data = le32(4)),
                    // Transfer: accounts [from, to]; data u32 LE 2, then u64 LE lamports.
                    Ix(program = 3, accounts = listOf(0, 2), data = le32(2) + le64(1_500_000_000L)),
                ),
            ),
        )
        assertEquals(
            listOf(
                SolanaInstruction.AdvanceNonce(at(SYSTEM), nonceAccount = at(NONCE), authority = at(PAYER)),
                SolanaInstruction.SystemTransfer(at(SYSTEM), from = at(PAYER), to = at(RECIPIENT), lamports = 1_500_000_000uL),
            ),
            review.instructions,
        )
        assertEquals(listOf(SolanaWarning.DURABLE_NONCE), review.warnings)
        // The recent blockhash field holds the nonce value.
        assertContentEquals(NONCE_VALUE, review.message!!.recentBlockhash)
    }

    /** CreateAccount (index 0) and InitializeNonceAccount (index 6) decode as one setup, with its rent, its authority and its payer. */
    @Test
    fun aNonceAccountSetupIsOneInstructionWithItsRentAuthorityAndPayer() {
        val review = review(setup(authority = Base58.decode(PAYER)))
        assertEquals(
            listOf(
                SolanaInstruction.CreateNonceAccount(
                    at(SYSTEM),
                    funder = at(PAYER),
                    account = at(NONCE),
                    lamports = 1_447_680uL,
                    authority = at(PAYER),
                ),
            ),
            review.instructions,
        )
        assertEquals(emptyList(), review.warnings)
    }

    /** The authority is key 5, which is not the signer. The review warns at error level, and the request is not refused. */
    @Test
    fun aNonceAdvanceWithAnotherAuthorityWarnsAndStillReviews() {
        val review = review(
            legacy(
                keys = listOf(PAYER, NONCE, RECIPIENT, SYSTEM, RECENT_BLOCKHASHES, OTHER),
                readonly = 3,
                instructions = listOf(
                    Ix(program = 3, accounts = listOf(1, 4, 5), data = le32(4)),
                    Ix(program = 3, accounts = listOf(0, 2), data = le32(2) + le64(1_500_000_000L)),
                ),
            ),
        )
        assertEquals(listOf(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER, SolanaWarning.DURABLE_NONCE), review.warnings)
        assertEquals(at(OTHER), assertIs<SolanaInstruction.AdvanceNonce>(review.instructions[0]).authority)
    }

    /** The InitializeNonceAccount data names another key as the authority. The review warns at error level and still shows. */
    @Test
    fun aNonceSetupWithAnotherAuthorityWarnsAndStillReviews() {
        val review = review(setup(authority = Base58.decode(OTHER)))
        assertEquals(listOf(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER), review.warnings)
        assertEquals(at(OTHER), assertIs<SolanaInstruction.CreateNonceAccount>(review.instructions.single()).authority)
    }

    /**
     * Each shape that is not the exact nonce layout stays raw, so no nonce instruction is read and the review warns about its program.
     * Keys: payer 0 (signer), nonce 1, System 2, RecentBlockhashes 3, Rent 4.
     */
    @Test
    fun aNonceLookalikeStaysRawAndWarnsAboutItsProgram() {
        val keys = listOf(PAYER, NONCE, SYSTEM, RECENT_BLOCKHASHES, RENT)
        val transfer = Ix(program = 2, accounts = listOf(0, 1), data = le32(2) + le64(5L))
        val create = Ix(program = 2, accounts = listOf(0, 1), data = le32(0) + le64(1_447_680L) + le64(80L) + ByteArray(32))
        val initialize = Ix(program = 2, accounts = listOf(1, 3, 4), data = le32(6) + Base58.decode(PAYER))
        val lookalikes = mapOf(
            // The network reads a durable nonce from the first instruction only, so an advance after another instruction is raw.
            "an advance that is not first" to listOf(transfer, Ix(program = 2, accounts = listOf(1, 3, 0), data = le32(4))),
            "an advance on another sysvar" to listOf(Ix(program = 2, accounts = listOf(1, 0, 0), data = le32(4))),
            "an advance with extra data" to listOf(Ix(program = 2, accounts = listOf(1, 3, 0), data = le32(4) + byteArrayOf(0))),
            "a setup with another space" to listOf(
                Ix(program = 2, accounts = listOf(0, 1), data = le32(0) + le64(1_447_680L) + le64(81L) + ByteArray(32)),
                initialize,
            ),
            "a setup owned by another program" to listOf(
                Ix(program = 2, accounts = listOf(0, 1), data = le32(0) + le64(1_447_680L) + le64(80L) + Base58.decode(PAYER)),
                initialize,
            ),
            "a setup that initializes another account" to listOf(
                create,
                Ix(program = 2, accounts = listOf(0, 3, 4), data = le32(6) + Base58.decode(PAYER)),
            ),
            "an initialize with no create before it" to listOf(initialize),
        )
        for ((name, instructions) in lookalikes) {
            val review = review(legacy(keys = keys, instructions = instructions, readonly = 3))
            assertTrue(
                review.instructions.none { it is SolanaInstruction.AdvanceNonce || it is SolanaInstruction.CreateNonceAccount },
                name,
            )
            assertTrue(SolanaWarning.UNKNOWN_PROGRAM in review.warnings, name)
        }
    }

    /**
     * Known answers. The two sysvar ids decode to their 32-byte keys, the System Program is 32 zero bytes, and the rent of an 80-byte
     * account is the rent-exempt minimum: (128 + 80) bytes at 3,480 lamports per byte-year, for two years.
     */
    @Test
    fun theSysvarAddressesAndTheNonceRentAreTheKnownAnswers() {
        assertEquals("06a7d517192c568ee08a845f73d29788cf035c3145b21ab344d8062ea9400000", Base58.decode(RECENT_BLOCKHASHES).toHex())
        assertEquals("06a7d517192c5c51218cc94c3d4af17f58daee089ba1fd44e3dbd98a00000000", Base58.decode(RENT).toHex())
        assertEquals(ByteArray(32).toHex(), Base58.decode(SYSTEM).toHex())
        assertEquals(1_447_680L, (128L + 80L) * 3_480L * 2L)
    }

    /**
     * CreateAccountWithSeed then InitializeNonceAccount decode as one setup. The new account is the address the seed gives, which the
     * test computes here as SHA-256 of the base, the seed and the System owner, in that order. The setup names its base, its seed, its
     * rent, its authority and its payer, and the payer is also the base.
     */
    @Test
    fun aSeededNonceSetupIsOneInstructionWithItsBaseSeedRentAuthorityAndPayer() {
        val review = review(seededSetup())
        assertEquals(
            listOf(
                SolanaInstruction.CreateNonceAccount(
                    at(SYSTEM),
                    funder = at(PAYER),
                    account = at(seededAddress(PAYER, SEED.toByteArray())),
                    lamports = 1_447_680uL,
                    authority = at(PAYER),
                    seed = SolanaInstruction.NonceSeed(base = at(PAYER), text = SEED),
                ),
            ),
            review.instructions,
        )
        assertEquals(emptyList(), review.warnings)
    }

    /** The longest seed a System instruction takes is 32 bytes, and a setup with a 32-byte seed is still a setup. */
    @Test
    fun aSeedOfThirtyTwoBytesIsTheLongestSetup() {
        val review = review(seededSetup(seed = ByteArray(32) { 0x62 }))
        assertEquals(
            SolanaInstruction.NonceSeed(base = at(PAYER), text = "b".repeat(32)),
            assertIs<SolanaInstruction.CreateNonceAccount>(review.instructions.single()).seed,
        )
    }

    /**
     * Each seeded shape that is not the exact layout stays raw, and the review warns about its program: a new account that is not the
     * seeded address, a seed of 33 bytes, a seed that is not UTF-8, another space, and another owner. The seed lookalikes name the address
     * their own seed gives, so the one field that is wrong is the only reason for raw.
     */
    @Test
    fun aSeededSetupLookalikeStaysRawAndWarnsAboutItsProgram() {
        val lookalikes = mapOf(
            "a new account that is not the seeded address" to seededSetup(nonce = NONCE),
            "a seed of 33 bytes" to seededSetup(seed = ByteArray(33) { 0x61 }),
            "a seed that is not UTF-8" to seededSetup(seed = byteArrayOf(0xff.toByte())),
            "a seeded setup with another space" to seededSetup(space = 81L),
            "a seeded setup owned by another program" to seededSetup(
                owner = Base58.decode(PAYER),
                nonce = seededAddress(PAYER, SEED.toByteArray(), owner = Base58.decode(PAYER)),
            ),
        )
        for ((name, signData) in lookalikes) {
            val review = review(signData)
            assertTrue(review.instructions.none { it is SolanaInstruction.CreateNonceAccount }, name)
            assertTrue(SolanaWarning.UNKNOWN_PROGRAM in review.warnings, name)
        }
    }

    /**
     * A known answer for createWithSeed. The input is 77 bytes: the base, 32 bytes of 0x01, then the seed "lumio-nonce-0", then the owner,
     * 32 zero bytes of the System Program. The expected value is SHA-256 of those bytes, from an independent SHA-256 over the same input.
     */
    @Test
    fun createWithSeedIsTheSha256OfBaseSeedAndOwner() {
        assertEquals(
            "7dee5a4151fbe74220046c3ddc8c51e4abb07cd8446172bf3ee9a9d4089df1fb",
            SolanaAccount.createWithSeed(ByteArray(32) { 1 }, SEED.toByteArray(), ByteArray(32)).toHex(),
        )
    }

    /** The one-time setup: CreateAccount for NONCE with 1,447,680 lamports, space 80 and the System Program as owner, then the authority. */
    private fun setup(authority: ByteArray): ByteArray = legacy(
        keys = listOf(PAYER, NONCE, SYSTEM, RECENT_BLOCKHASHES, RENT),
        signers = 2,
        readonly = 3,
        instructions = listOf(
            // CreateAccount: accounts [funder, new account]; data u32 LE 0, u64 LE lamports, u64 LE space, then the 32-byte owner.
            Ix(program = 2, accounts = listOf(0, 1), data = le32(0) + le64(1_447_680L) + le64(80L) + ByteArray(32)),
            // InitializeNonceAccount: accounts [nonce, RecentBlockhashes sysvar, Rent sysvar]; data u32 LE 6, then the 32-byte authority.
            Ix(program = 2, accounts = listOf(1, 3, 4), data = le32(6) + authority),
        ),
    )

    /**
     * CreateAccountWithSeed (index 3), then InitializeNonceAccount, for a nonce account at [nonce] derived from PAYER and [seed]. PAYER
     * pays, is the base, and is the authority. Keys: payer 0 (signer), the new account 1, System 2, RecentBlockhashes 3, Rent 4.
     */
    private fun seededSetup(
        seed: ByteArray = SEED.toByteArray(),
        nonce: String = seededAddress(PAYER, seed),
        space: Long = 80L,
        owner: ByteArray = ByteArray(32),
    ): ByteArray = legacy(
        keys = listOf(PAYER, nonce, SYSTEM, RECENT_BLOCKHASHES, RENT),
        readonly = 3,
        instructions = listOf(
            // CreateAccountWithSeed: accounts [funder, new account, base]; data u32 LE 3, the 32-byte base, the seed as a u64 LE length
            // and its bytes, u64 LE lamports, u64 LE space, then the 32-byte owner.
            Ix(
                program = 2,
                accounts = listOf(0, 1, 0),
                data = le32(3) + Base58.decode(PAYER) + le64(seed.size.toLong()) + seed + le64(1_447_680L) + le64(space) + owner,
            ),
            // InitializeNonceAccount: accounts [nonce, RecentBlockhashes sysvar, Rent sysvar]; data u32 LE 6, then the 32-byte authority.
            Ix(program = 2, accounts = listOf(1, 3, 4), data = le32(6) + Base58.decode(PAYER)),
        ),
    )

    /** The address CreateAccountWithSeed gives, in base58: SHA-256 of the 32-byte [base], then [seed], then the 32-byte [owner]. */
    private fun seededAddress(base: String, seed: ByteArray, owner: ByteArray = ByteArray(32)): String =
        Base58.encode(MessageDigest.getInstance("SHA-256").digest(Base58.decode(base) + seed + owner))

    /** A legacy message over [keys], by base58. The first [signers] keys sign, and the last [readonly] keys are read-only. */
    private fun legacy(keys: List<String>, instructions: List<Ix>, signers: Int = 1, readonly: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(signers.toByte(), 0, readonly.toByte()))
        out.write(keys.size)
        keys.forEach { out.write(Base58.decode(it)) }
        out.write(NONCE_VALUE)
        out.write(instructions.size)
        for (instruction in instructions) {
            out.write(instruction.program)
            out.write(instruction.accounts.size)
            out.write(instruction.accounts.map { it.toByte() }.toByteArray())
            out.write(instruction.data.size)
            out.write(instruction.data)
        }
        return out.toByteArray()
    }

    /** The review of [signData] for the wallet's one account, PAYER, which signs. The check must accept the request. */
    private fun review(signData: ByteArray): SolanaRequestReview {
        val request = SolSignRequest(
            requestId = ByteArray(16) { 0x22 },
            signData = signData,
            derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)),
            sourceFingerprint = 1,
            address = null,
            origin = null,
            signType = SolSignType.TRANSACTION,
        )
        return assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, listOf(Base58.decode(PAYER)))).review
    }

    private fun at(address: String): SolanaMessageAccount = SolanaMessageAccount.Static(address)

    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun le64(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /** One compiled instruction: the index of its program among the keys, the indices of its accounts, and its data. */
    private class Ix(val program: Int, val accounts: List<Int>, val data: ByteArray)

    private companion object {
        /** The keys by base58 address. NONCE is the nonce account, and OTHER is a key that does not sign. */
        const val PAYER = SolanaVectors.PAYER
        const val NONCE = SolanaVectors.SOURCE
        const val RECIPIENT = SolanaVectors.RECIPIENT
        const val OTHER = SolanaVectors.OWNER
        const val SYSTEM = SolanaVectors.SYSTEM

        /** The RecentBlockhashes and Rent sysvar ids, by base58. */
        const val RECENT_BLOCKHASHES = "SysvarRecentB1ockHashes11111111111111111111"
        const val RENT = "SysvarRent111111111111111111111111111111111"

        /** The nonce value. A durable-nonce message holds it in its recent blockhash field. */
        val NONCE_VALUE = ByteArray(32) { 0x5a }

        /** A seed for a nonce account made with CreateAccountWithSeed, in these tests. */
        const val SEED = "lumio-nonce-0"
    }
}
