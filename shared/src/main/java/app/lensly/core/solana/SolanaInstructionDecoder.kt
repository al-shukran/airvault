package app.lensly.core.solana

import app.lensly.core.wallet.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
private const val COMPUTE_BUDGET_PROGRAM = "ComputeBudget111111111111111111111111111111"
private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
private const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
private const val ASSOCIATED_TOKEN_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"

/**
 * Decodes each instruction of the message, in order. An instruction is decoded only when its program is a known one and it has
 * exactly the accounts and data that program expects. Everything else is [SolanaInstruction.Raw].
 */
fun SolanaMessage.decodeInstructions(): List<SolanaInstruction> = instructions.map { decodeInstruction(it) }

private fun SolanaMessage.decodeInstruction(instruction: SolanaMessage.CompiledInstruction): SolanaInstruction {
    val program = account(instruction.programIdIndex)
    val accounts = instruction.accountIndices.map { account(it) }
    val data = instruction.data
    // A program loaded from a lookup table cannot be named from the message, so it stays raw.
    val known = (program as? SolanaMessageAccount.Static)?.address
    val decoded = when (known) {
        SYSTEM_PROGRAM -> systemTransfer(program, accounts, data)
        COMPUTE_BUDGET_PROGRAM -> computeBudget(program, accounts, data)
        TOKEN_PROGRAM, TOKEN_2022_PROGRAM -> token(program, accounts, data)
        ASSOCIATED_TOKEN_PROGRAM -> associatedTokenCreate(program, accounts, data)
        else -> null
    }
    return decoded ?: SolanaInstruction.Raw(program, instruction.accountIndices, accounts, data.toHex())
}

/** Transfer: u32 LE index 2, then u64 LE lamports. Accounts are [from, to]. */
private fun systemTransfer(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
): SolanaInstruction? {
    if (data.size != 12 || u32(data, 0) != 2u || accounts.size != 2) return null
    return SolanaInstruction.SystemTransfer(program, from = accounts[0], to = accounts[1], lamports = u64(data, 4))
}

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
 * token program].
 */
private fun associatedTokenCreate(
    program: SolanaMessageAccount,
    accounts: List<SolanaMessageAccount>,
    data: ByteArray,
): SolanaInstruction? {
    if (accounts.size != 6) return null
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
