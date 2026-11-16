package app.lensly.app.sign

import app.lensly.core.solana.SolanaInstruction
import app.lensly.core.solana.SolanaMessageAccount
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaWarning
import app.lensly.core.wallet.toHex
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The label of the signing account's row. */
private const val SIGNER = "Signer"

/** The label of the fee payer's row. */
private const val FEE_PAYER = "Fee payer"

/** The label of the row that names an instruction's program by its ID. */
private const val PROGRAM = "Program"

/** The decimal places of a lamport: one SOL is 10^9 lamports. */
private const val LAMPORT_DECIMALS = 9

/**
 * The label and value rows a Solana review shows, in order. A transaction gives the signer, the fee payer and then each instruction,
 * with its accounts. A message gives the signer and the message as the owner reads it. The review screen and the Review Record both show
 * these rows, so they say the same thing.
 */
fun solanaRows(review: SolanaRequestReview): List<Pair<String, String>> = buildList {
    add(SIGNER to review.signerAddress)
    val feePayer = review.feePayer
    if (feePayer == null) {
        add("Message" to solanaMessageText(review.messageBytes ?: ByteArray(0)))
    } else {
        add(FEE_PAYER to feePayer)
        review.instructions.forEach { addAll(instructionRows(it)) }
    }
}

/**
 * The text an account shows as: its base58 address. An account that comes from an address lookup table shows the table, the index and
 * the note that Lensly cannot check it offline, as WI-24 requires.
 */
fun solanaAccountText(account: SolanaMessageAccount): String = when (account) {
    is SolanaMessageAccount.Static -> account.address
    is SolanaMessageAccount.Lookup ->
        "lookup table ${account.tableAddress}, index ${account.tableIndex} (can't be checked offline)"
}

/**
 * The text of a message to sign, when every character can be read by eye, as the ETH messages are. Any other message shows its bytes
 * as 0x hex.
 */
fun solanaMessageText(bytes: ByteArray): String = printableText(bytes) ?: ("0x" + bytes.toHex())

/** The message as text, when it is UTF-8 whose every character is [isPrintable]. Null for any other bytes. */
internal fun printableText(bytes: ByteArray): String? = try {
    val text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    text.takeIf { it.isNotEmpty() && it.all(::isPrintable) }
} catch (e: CharacterCodingException) {
    null
}

/**
 * The rows one instruction shows. A known instruction names its program first, by its ID. A raw instruction already names its program
 * as unknown, and shows its data and its accounts, as bytes.
 */
private fun instructionRows(instruction: SolanaInstruction): List<Pair<String, String>> {
    val details = detailRows(instruction)
    if (instruction is SolanaInstruction.Raw) return details
    return listOf(PROGRAM to solanaAccountText(instruction.programId)) + details
}

private fun detailRows(instruction: SolanaInstruction): List<Pair<String, String>> = when (instruction) {
    is SolanaInstruction.SystemTransfer -> listOf(
        "SOL transfer" to "${lamportsText(instruction.lamports)} SOL",
        "From" to solanaAccountText(instruction.from),
        "To" to solanaAccountText(instruction.to),
    )
    // A Token Transfer carries no mint, so its amount is in base units and the token is not named.
    is SolanaInstruction.TokenTransfer -> listOf(
        "Token transfer" to "${instruction.amount} base units. The token is not named in this instruction.",
        "From" to solanaAccountText(instruction.source),
        "To" to solanaAccountText(instruction.destination),
        "Authority" to solanaAccountText(instruction.authority),
    )
    is SolanaInstruction.TokenTransferChecked -> listOf(
        "Token transfer" to "${decimalText(instruction.amount, instruction.decimals)} of ${solanaAccountText(instruction.mint)}",
        "From" to solanaAccountText(instruction.source),
        "To" to solanaAccountText(instruction.destination),
        "Authority" to solanaAccountText(instruction.authority),
    )
    is SolanaInstruction.AssociatedTokenCreate -> listOf(
        "Create token account" to
            "for ${solanaAccountText(instruction.owner)} of ${solanaAccountText(instruction.mint)}",
    )
    is SolanaInstruction.SetComputeUnitLimit -> listOf("Compute limit" to "${instruction.units} units")
    is SolanaInstruction.SetComputeUnitPrice ->
        listOf("Compute price" to "${instruction.microLamports} micro-lamports per unit")
    is SolanaInstruction.Raw -> listOf(
        "Unknown program" to solanaAccountText(instruction.programId),
        "Instruction data" to "0x" + instruction.dataHex,
        "Accounts" to instruction.accounts.joinToString(", ") { solanaAccountText(it) }.ifEmpty { "none" },
    )
}

/** A lamport count as SOL, with no trailing zeros. */
private fun lamportsText(lamports: ULong): String = decimalText(lamports, LAMPORT_DECIMALS)

private fun decimalText(amount: ULong, decimals: Int): String =
    BigDecimal(amount.toString()).movePointLeft(decimals).stripTrailingZeros().toPlainString()

/** The sentence at the top of the review. It names the one SOL transfer when there is exactly one, and otherwise says what is signed. */
fun solanaSentence(review: SolanaRequestReview): String {
    val message = review.messageBytes
    if (message != null) {
        return if (printableText(message) != null) "Signs a message as text." else "Signs a message as bytes."
    }
    val transfers = review.instructions.filterIsInstance<SolanaInstruction.SystemTransfer>()
    val only = transfers.singleOrNull()
    return if (only != null) {
        "Sends ${lamportsText(only.lamports)} SOL to ${solanaAccountText(only.to)}."
    } else {
        "Signs a transaction with ${review.instructions.size} instructions."
    }
}

/** The short facts under the sentence: the instruction count and the fee payer, for a transaction. */
fun solanaFacts(review: SolanaRequestReview): List<Pair<String, String>> {
    val feePayer = review.feePayer ?: return emptyList()
    return listOf("Instructions" to review.instructions.size.toString(), FEE_PAYER to feePayer)
}

/** The text of each warning the review shows, in the order the review holds them. */
fun solanaWarningText(warning: SolanaWarning): String = when (warning) {
    SolanaWarning.UNKNOWN_PROGRAM ->
        "An instruction calls a program Lensly does not read. It is shown as raw bytes, and Lensly cannot say what it does."
    SolanaWarning.LOOKUP_TABLE ->
        "Some accounts come from address lookup tables. Lensly cannot check those addresses offline."
}
