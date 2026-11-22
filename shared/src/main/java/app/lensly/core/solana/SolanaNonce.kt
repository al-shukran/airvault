package app.lensly.core.solana

import app.airvault.chains.solana.createWithSeed
import fr.acinq.bitcoin.Base58
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The durable nonces of Lumio's Solana sends, built byte by byte. Lumio holds no key, so the nonce account of an account is the address that
 * the account's own key and a fixed seed give: createWithSeed(key, [SEED], System). Lensly signs for that key alone, so one signature covers the
 * one-time setup, which creates and funds the nonce account and names the key as its authority, and every transfer after it, which advances the
 * nonce first and names the nonce value as its recent blockhash. Nothing here signs.
 */
object SolanaNonce {
    /** The seed of an account's nonce account. */
    const val SEED = "lumio-nonce-0"

    /** The size of a nonce account's data: the 80 bytes that an initialized nonce holds. */
    const val ACCOUNT_BYTES = 80L

    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    private const val RECENT_BLOCKHASHES = "SysvarRecentB1ockHashes11111111111111111111"
    private const val RENT = "SysvarRent111111111111111111111111111111111"
    private const val KEY_BYTES = 32
    private const val BLOCKHASH_BYTES = 32

    /** System Program indices: CreateAccountWithSeed is 3, AdvanceNonceAccount is 4, Transfer is 2, InitializeNonceAccount is 6. */
    private const val TRANSFER = 2
    private const val CREATE_ACCOUNT_WITH_SEED = 3
    private const val ADVANCE_NONCE_ACCOUNT = 4
    private const val INITIALIZE_NONCE_ACCOUNT = 6

    /** The nonce account's version and state, as the runtime writes them: the current version, and an initialized nonce. */
    private const val CURRENT_VERSION = 1
    private const val INITIALIZED = 1

    /** The address of the nonce account of [account], a 32-byte key: createWithSeed of the key, [SEED] and the System Program. */
    fun accountOf(account: ByteArray): ByteArray {
        require(account.size == KEY_BYTES) { "A Solana address is 32 bytes." }
        return createWithSeed(account, SEED.toByteArray(Charsets.UTF_8), Base58.decode(SYSTEM_PROGRAM))
    }

    /**
     * The one-time setup of [account]'s nonce account, at [recentBlockhash]. CreateAccountWithSeed funds the nonce account with [rentLamports],
     * gives it the System Program as owner and the 80 bytes of a nonce, and names [account] as its base. Then InitializeNonceAccount names
     * [account] as the authority. The account pays and signs as the base, so it is the only signer.
     */
    fun setupMessage(account: ByteArray, recentBlockhash: ByteArray, rentLamports: Long): ByteArray {
        require(rentLamports >= 0) { "The rent is not negative." }
        val nonce = accountOf(account)
        val seed = SEED.toByteArray(Charsets.UTF_8)
        val create = ByteBuffer.allocate(4 + KEY_BYTES + 8 + seed.size + 8 + 8 + KEY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CREATE_ACCOUNT_WITH_SEED)
            .put(account)
            .putLong(seed.size.toLong())
            .put(seed)
            .putLong(rentLamports)
            .putLong(ACCOUNT_BYTES)
            .put(ByteArray(KEY_BYTES))
            .array()
        val initialize = ByteBuffer.allocate(4 + KEY_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(INITIALIZE_NONCE_ACCOUNT).put(account).array()
        return compile(
            keys = listOf(account, nonce, Base58.decode(SYSTEM_PROGRAM), Base58.decode(RECENT_BLOCKHASHES), Base58.decode(RENT)),
            readonlyUnsigned = 3,
            recentBlockhash = recentBlockhash,
            instructions = listOf(
                // CreateAccountWithSeed: accounts [funder, new account, base]; the funder and the base are the account, key 0.
                Instruction(program = 2, accounts = listOf(0, 1, 0), data = create),
                // InitializeNonceAccount: accounts [nonce account, RecentBlockhashes sysvar, Rent sysvar].
                Instruction(program = 2, accounts = listOf(1, 3, 4), data = initialize),
            ),
        )
    }

    /**
     * The transfer of [lamports] from [account] to [recipient], as Lumio sends it. AdvanceNonceAccount is the first instruction, on the nonce
     * account of [account] with [account] as its authority, and then the transfer follows. [nonceValue] is the recent blockhash. The accounts are
     * the account (a writable signer), its nonce account and the recipient (writable), then the System Program and the RecentBlockhashes sysvar. When
     * [priority] is set, the ComputeBudget program follows them as a read-only key, and SetComputeUnitPrice comes right after the advance.
     */
    fun transferMessage(account: ByteArray, recipient: ByteArray, lamports: Long, nonceValue: ByteArray, priority: Boolean = false): ByteArray {
        require(recipient.size == KEY_BYTES) { "A Solana address is 32 bytes." }
        require(lamports >= 0) { "An amount is not negative." }
        val nonce = accountOf(account)
        require(!recipient.contentEquals(account) && !recipient.contentEquals(nonce)) { "The recipient is this account." }
        val transfer = ByteBuffer.allocate(4 + 8).order(ByteOrder.LITTLE_ENDIAN).putInt(TRANSFER).putLong(lamports).array()
        val keys = listOf(account, nonce, recipient, Base58.decode(SYSTEM_PROGRAM), Base58.decode(RECENT_BLOCKHASHES))
        return compile(
            keys = if (priority) keys + SolanaPriorityFee.programKey() else keys,
            readonlyUnsigned = if (priority) 3 else 2,
            recentBlockhash = nonceValue,
            instructions = listOfNotNull(
                // AdvanceNonceAccount: accounts [nonce account, RecentBlockhashes sysvar, authority]; data u32 LE 4.
                Instruction(program = 3, accounts = listOf(1, 4, 0), data = u32(ADVANCE_NONCE_ACCOUNT)),
                // SetComputeUnitPrice: the ComputeBudget program, key 5, with no accounts.
                if (priority) Instruction(program = 5, accounts = emptyList(), data = SolanaPriorityFee.instructionData()) else null,
                // Transfer: accounts [from, to]; data u32 LE 2, then the u64 LE lamports.
                Instruction(program = 3, accounts = listOf(0, 2), data = transfer),
            ),
        )
    }

    /**
     * The nonce value that a nonce account holds, when [data] is an initialized nonce account whose authority is [authority]. The data is the
     * u32 LE version (1), the u32 LE state (1, initialized), the 32-byte authority, the 32-byte nonce value, then the u64 LE fee. Null for any other data.
     */
    fun nonceValue(data: ByteArray, authority: ByteArray): ByteArray? {
        if (data.size != ACCOUNT_BYTES.toInt() || authority.size != KEY_BYTES) return null
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (header.getInt(0) != CURRENT_VERSION || header.getInt(4) != INITIALIZED) return null
        if (!data.copyOfRange(8, 40).contentEquals(authority)) return null
        return data.copyOfRange(40, 72)
    }

    /** One compiled instruction: the index of its program among the keys, the indices of its accounts, and its data. */
    internal class Instruction(val program: Int, val accounts: List<Int>, val data: ByteArray)

    /**
     * A legacy message with one required signature, the first key, and [readonlyUnsigned] read-only keys at the end of [keys]. The keys and the
     * instructions are written in the order given.
     */
    internal fun compile(keys: List<ByteArray>, readonlyUnsigned: Int, recentBlockhash: ByteArray, instructions: List<Instruction>): ByteArray {
        require(recentBlockhash.size == BLOCKHASH_BYTES) { "A blockhash is 32 bytes." }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, readonlyUnsigned.toByte()))
        out.write(compactU16(keys.size))
        keys.forEach { out.write(it) }
        out.write(recentBlockhash)
        out.write(compactU16(instructions.size))
        for (instruction in instructions) {
            out.write(instruction.program)
            out.write(compactU16(instruction.accounts.size))
            out.write(instruction.accounts.map { it.toByte() }.toByteArray())
            out.write(compactU16(instruction.data.size))
            out.write(instruction.data)
        }
        return out.toByteArray()
    }

    private fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
