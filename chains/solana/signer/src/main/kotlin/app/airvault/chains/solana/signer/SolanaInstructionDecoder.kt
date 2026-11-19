package app.airvault.chains.solana.signer

import app.airvault.chains.solana.createWithSeed
import fr.acinq.bitcoin.Base58
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
internal const val COMPUTE_BUDGET_PROGRAM = "ComputeBudget111111111111111111111111111111"
private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
private const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
private const val ASSOCIATED_TOKEN_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
private const val RECENT_BLOCKHASHES_SYSVAR = "SysvarRecentB1ockHashes11111111111111111111"
private const val RENT_SYSVAR = "SysvarRent111111111111111111111111111111111"

/** The largest seed that CreateAccountWithSeed takes, in bytes. */
private const val MAX_SEED_BYTES = 32

/**
 * Decodes each instruction of the message, in order. An instruction is decoded only when its program is a known one and it has
 * exactly the accounts and data that program expects. Everything else is [SolanaInstruction.Raw]. The one-time nonce setup is two
 * instructions that decode as one [SolanaInstruction.CreateNonceAccount], so the list can be shorter than the message's.
 */
fun SolanaMessage.decodeInstructions(): List<SolanaInstruction> {
    val decoded = ArrayList<SolanaInstruction>(instructions.size)
    var position = 0
    while (position < instructions.size) {
        val setup = nonceCreate(position)
        if (setup != null) {
            decoded.add(setup)
            position += 2
        } else {
            decoded.add(decodeInstruction(instructions[position], first = position == 0))
            position += 1
        }
    }
    return decoded
}

private fun SolanaMessage.decodeInstruction(instruction: SolanaMessage.CompiledInstruction, first: Boolean): SolanaInstruction {
    val program = account(instruction.programIdIndex)
    val accounts = instruction.accountIndices.map { account(it) }
    val data = instruction.data
    // A program loaded from a lookup table cannot be named from the message, so it stays raw.
    val known = (program as? SolanaMessageAccount.Static)?.address
    val decoded = when (known) {
        SYSTEM_PROGRAM -> systemInstruction(program, accounts, data, first)
        COMPUTE_BUDGET_PROGRAM -> computeBudget(program, accounts, data)
        TOKEN_PROGRAM, TOKEN_2022_PROGRAM -> token(program, accounts, data)
        ASSOCIATED_TOKEN_PROGRAM -> associatedTokenCreate(program, accounts, data)
        else -> null
    }
    val readProgram = known != null && known in READ_PROGRAMS
    return decoded ?: SolanaInstruction.Raw(program, instruction.accountIndices, accounts, data.toHex(), knownProgram = readProgram)
}

/** The programs this decoder reads in some of their instructions. An instruction of one of them that is not read is not an unknown program. */
private val READ_PROGRAMS = setOf(SYSTEM_PROGRAM, COMPUTE_BUDGET_PROGRAM, TOKEN_PROGRAM, TOKEN_2022_PROGRAM, ASSOCIATED_TOKEN_PROGRAM)

/**
 * The System Program instructions Lensly reads. Transfer is index 2. AdvanceNonceAccount is index 4, and it is read only as the first
 * instruction, since that is where the network reads a durable nonce. Every other index stays raw.
 */
private fun systemInstruction(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
    first: Boolean,
): SolanaInstruction? {
    if (data.size < 4) return null
    return when (u32(data, 0)) {
        2u -> systemTransfer(program, accounts, data)
        4u -> if (first) advanceNonce(program, accounts, data) else null
        else -> null
    }
}

/** Transfer: u32 LE index 2, then u64 LE lamports. Accounts are [from, to]. */
private fun systemTransfer(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
): SolanaInstruction? {
    if (data.size != 12 || accounts.size != 2) return null
    return SolanaInstruction.SystemTransfer(program, from = accounts[0], to = accounts[1], lamports = u64(data, 4))
}

/** AdvanceNonceAccount: u32 LE index 4, and no other data. Accounts are [nonce account, RecentBlockhashes sysvar, nonce authority]. */
private fun advanceNonce(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
): SolanaInstruction? {
    if (data.size != 4 || accounts.size != 3 || !accounts[1].isStatic(RECENT_BLOCKHASHES_SYSVAR)) return null
    return SolanaInstruction.AdvanceNonce(program, nonceAccount = accounts[0], authority = accounts[2])
}

/**
 * The one-time setup of a durable nonce at [position]: System CreateAccount or CreateAccountWithSeed, then System InitializeNonceAccount
 * for the account the first one creates. Null unless the two match, and then each decodes on its own.
 *
 * CreateAccount is index 0: u32 LE 0, u64 LE lamports, u64 LE space, then the 32-byte owner. Accounts are [funder, new account].
 * CreateAccountWithSeed is index 3: u32 LE 3, the 32-byte base, the seed (u64 LE length, then its UTF-8 bytes), u64 LE lamports, u64 LE
 * space, then the 32-byte owner. Accounts are [funder, new account, base]. InitializeNonceAccount is index 6: u32 LE 6, then the 32-byte
 * authority. Accounts are [nonce account, RecentBlockhashes sysvar, Rent sysvar]. In both setups the space is 80 bytes, the size of a
 * nonce account, and the owner is the System Program.
 */
private fun SolanaMessage.nonceCreate(position: Int): SolanaInstruction? {
    if (position + 1 >= instructions.size) return null
    val create = instructions[position]
    val initialize = instructions[position + 1]
    val program = account(create.programIdIndex)
    if (!program.isStatic(SYSTEM_PROGRAM) || !account(initialize.programIdIndex).isStatic(SYSTEM_PROGRAM)) return null
    val initializeAccounts = initialize.accountIndices.map { account(it) }
    val initializeData = initialize.data
    if (initializeData.size != 36 || initializeAccounts.size != 3 || u32(initializeData, 0) != 6u) return null
    if (!initializeAccounts[1].isStatic(RECENT_BLOCKHASHES_SYSVAR) || !initializeAccounts[2].isStatic(RENT_SYSVAR)) return null
    val created = createAccount(create) ?: createAccountWithSeed(create) ?: return null
    if (initializeAccounts[0] != created.account) return null
    return SolanaInstruction.CreateNonceAccount(
        programId = program,
        funder = created.funder,
        account = created.account,
        lamports = created.lamports,
        authority = SolanaMessageAccount.Static(Base58.encode(initializeData.copyOfRange(4, 36))),
        seed = created.seed,
    )
}

/** What a setup creates: who pays for it, the new account, its rent in lamports, and the seed when the account is derived from one. */
private class NonceCreation(
    val funder: SolanaMessageAccount,
    val account: SolanaMessageAccount,
    val lamports: ULong,
    val seed: SolanaInstruction.NonceSeed?,
)

/** CreateAccount for a nonce account: u32 LE 0, u64 LE lamports, u64 LE space 80, then the 32-byte System owner. Accounts are [funder, new]. */
private fun SolanaMessage.createAccount(create: SolanaMessage.CompiledInstruction): NonceCreation? {
    val data = create.data
    if (data.size != 52 || u32(data, 0) != 0u || u64(data, 12) != 80uL) return null
    if (Base58.encode(data.copyOfRange(20, 52)) != SYSTEM_PROGRAM) return null
    val accounts = create.accountIndices.map { account(it) }
    if (accounts.size != 2) return null
    return NonceCreation(funder = accounts[0], account = accounts[1], lamports = u64(data, 4), seed = null)
}

/**
 * CreateAccountWithSeed for a nonce account. The seed is at most 32 bytes and UTF-8, and the new account must be the address that
 * [createWithSeed] gives for the base and the seed, with the System Program as owner. Accounts are [funder, new, base].
 */
private fun SolanaMessage.createAccountWithSeed(create: SolanaMessage.CompiledInstruction): NonceCreation? {
    val data = create.data
    if (data.size < 44 || u32(data, 0) != 3u) return null
    val seedSize = u64(data, 36)
    if (seedSize > MAX_SEED_BYTES.toULong()) return null
    val lamportsAt = 44 + seedSize.toInt()
    if (data.size != lamportsAt + 48 || u64(data, lamportsAt + 8) != 80uL) return null
    if (Base58.encode(data.copyOfRange(lamportsAt + 16, lamportsAt + 48)) != SYSTEM_PROGRAM) return null
    val seedBytes = data.copyOfRange(44, lamportsAt)
    val seed = seedText(seedBytes) ?: return null
    val accounts = create.accountIndices.map { account(it) }
    if (accounts.size != 3) return null
    val base = accounts[2] as? SolanaMessageAccount.Static ?: return null
    val created = accounts[1] as? SolanaMessageAccount.Static ?: return null
    val derived = createWithSeed(Base58.decode(base.address), seedBytes, Base58.decode(SYSTEM_PROGRAM))
    if (Base58.encode(derived) != created.address) return null
    return NonceCreation(
        funder = accounts[0],
        account = created,
        lamports = u64(data, lamportsAt),
        seed = SolanaInstruction.NonceSeed(base = base, text = seed),
    )
}

/** The seed as text, when its bytes are UTF-8, as a System seed must be. Null for any other bytes. */
private fun seedText(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}

/** Whether this account is the key [expected] that the message lists. An account from a lookup table is not one. */
private fun SolanaMessageAccount.isStatic(expected: String): Boolean = (this as? SolanaMessageAccount.Static)?.address == expected

/** SetComputeUnitLimit is tag 2 with a u32; SetComputeUnitPrice is tag 3 with a u64. The program takes no accounts. */
private fun computeBudget(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
): SolanaInstruction? {
    if (accounts.isNotEmpty()) return null
    return when (data.getOrNull(0)?.toInt()) {
        2 -> if (data.size == 5) SolanaInstruction.SetComputeUnitLimit(program, u32(data, 1)) else null
        3 -> if (data.size == 9) SolanaInstruction.SetComputeUnitPrice(program, u64(data, 1)) else null
        else -> null
    }
}

/**
 * Transfer (tag 3): accounts [source, destination, authority]. TransferChecked (tag 12): accounts [source, mint, destination,
 * authority], then one decimals byte. A Token-2022 transfer hook adds accounts, so any other count stays raw.
 */
private fun token(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
): SolanaInstruction? = when (data.getOrNull(0)?.toInt()) {
    3 -> if (data.size == 9 && accounts.size == 3) {
        SolanaInstruction.TokenTransfer(
            program,
            source = accounts[0],
            destination = accounts[1],
            authority = accounts[2],
            amount = u64(data, 1),
        )
    } else {
        null
    }
    12 -> if (data.size == 10 && accounts.size == 4) {
        SolanaInstruction.TokenTransferChecked(
            program,
            source = accounts[0],
            mint = accounts[1],
            destination = accounts[2],
            authority = accounts[3],
            amount = u64(data, 1),
            decimals = data[9].toUByte().toInt(),
        )
    } else {
        null
    }
    else -> null
}

/**
 * Create takes empty data (or tag 0), and CreateIdempotent takes tag 1. Accounts are [payer, account, owner, mint, system program,
 * token program]. The last two are the programs the create calls, so they must be the System Program and a token program, or the
 * instruction is not decoded and the review flags it as an unknown program.
 */
private fun associatedTokenCreate(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
): SolanaInstruction? {
    if (accounts.size != 6) return null
    val systemProgram = (accounts[4] as? SolanaMessageAccount.Static)?.address
    val tokenProgram = (accounts[5] as? SolanaMessageAccount.Static)?.address
    if (systemProgram != SYSTEM_PROGRAM || (tokenProgram != TOKEN_PROGRAM && tokenProgram != TOKEN_2022_PROGRAM)) return null
    val idempotent = when {
        data.isEmpty() || data.contentEquals(byteArrayOf(0)) -> false
        data.contentEquals(byteArrayOf(1)) -> true
        else -> return null
    }
    return SolanaInstruction.AssociatedTokenCreate(
        program,
        payer = accounts[0],
        account = accounts[1],
        owner = accounts[2],
        mint = accounts[3],
        idempotent = idempotent,
    )
}

private fun u32(data: ByteArray, at: Int): UInt =
    ByteBuffer.wrap(data, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()

private fun u64(data: ByteArray, at: Int): ULong =
    ByteBuffer.wrap(data, at, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
