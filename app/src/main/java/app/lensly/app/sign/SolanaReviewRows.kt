package app.lensly.app.sign

import app.lensly.app.addressesIn
import app.lensly.core.solana.SolanaInstruction
import app.lensly.core.solana.SolanaMessageAccount
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaToken
import app.lensly.core.solana.SolanaWarning
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.shortAddress
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

/** The label of the priority fee, in the facts under the sentence. */
private const val PRIORITY_FEE = "Priority fee"

/** The label of the mint, in the facts under the sentence, when one mint is named. */
private const val MINT = "Mint"

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
        review.instructions.forEach { addAll(instructionRows(it, review)) }
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
private fun instructionRows(instruction: SolanaInstruction, review: SolanaRequestReview): List<Pair<String, String>> {
    val details = detailRows(instruction, review)
    if (instruction is SolanaInstruction.Raw) return details
    return listOf(PROGRAM to solanaAccountText(instruction.programId)) + details
}

private fun detailRows(instruction: SolanaInstruction, review: SolanaRequestReview): List<Pair<String, String>> = when (instruction) {
    is SolanaInstruction.SystemTransfer -> listOf(
        "SOL transfer" to "${lamportsText(instruction.lamports)} SOL",
        "From" to solanaAccountText(instruction.from),
        "To" to solanaAccountText(instruction.to),
    )
    // A plain Transfer names no mint, so its amount is in base units and its decimals are not known.
    is SolanaInstruction.TokenTransfer -> listOf(
        "Token transfer" to "${instruction.amount} base units, decimals unknown",
        "From token account" to solanaAccountText(instruction.source),
        "To token account" to solanaAccountText(instruction.destination),
        "Authority" to solanaAccountText(instruction.authority),
    )
    is SolanaInstruction.TokenTransferChecked -> {
        val token = review.tokenOf(instruction.mint)
        listOf(
            "Token transfer" to tokenAmountText(instruction.amount, instruction.decimals, token),
            "Token" to tokenNameText(token),
            "Mint" to solanaAccountText(instruction.mint),
            "From token account" to solanaAccountText(instruction.source),
            "To token account" to solanaAccountText(instruction.destination),
            "Authority" to solanaAccountText(instruction.authority),
        )
    }
    // The owner is the wallet the token account is made for. The token account is the one the program creates.
    is SolanaInstruction.AssociatedTokenCreate -> {
        val token = review.tokenOf(instruction.mint)
        listOf(
            "Create token account" to if (instruction.idempotent) "only if missing" else "fails if it exists",
            "Owner" to solanaAccountText(instruction.owner),
            "Token" to tokenNameText(token),
            "Mint" to solanaAccountText(instruction.mint),
            "Token account" to solanaAccountText(instruction.account),
            "Paid by" to solanaAccountText(instruction.payer),
        )
    }
    is SolanaInstruction.AdvanceNonce -> listOf(
        "Uses durable nonce account" to solanaAccountText(instruction.nonceAccount),
        "Nonce authority" to solanaAccountText(instruction.authority),
    )
    is SolanaInstruction.CreateNonceAccount -> listOf(
        "Create a nonce account" to solanaAccountText(instruction.account),
        "Rent" to "${lamportsText(instruction.lamports)} SOL",
        "Nonce authority" to solanaAccountText(instruction.authority),
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

/**
 * An amount in a token's units, with the token's symbol when the token is known. An unknown token's amount keeps the decimals the
 * transfer names, which the chain checks, and says the token is unknown.
 */
private fun tokenAmountText(amount: ULong, decimals: Int, token: SolanaToken?): String {
    val number = decimalText(amount, decimals)
    return if (token == null) "$number of an unknown token" else "$number ${token.symbol}"
}

/** The name of a token, with its symbol in brackets. When the name is the symbol, the symbol alone. */
private fun tokenNameText(token: SolanaToken?): String = when {
    token == null -> "Unknown token"
    token.name.equals(token.symbol, ignoreCase = true) -> token.symbol
    else -> "${token.name} (${token.symbol})"
}

/**
 * The sentence at the top of the review. It names the one transfer when there is exactly one, in SOL or in a token, and otherwise says
 * what is signed.
 */
/** The addresses a Solana review shows, each one looked up in the Address Book. */
fun solanaCounterparties(review: SolanaRequestReview): List<String> = addressesIn(solanaRows(review).map { it.second })

fun solanaSentence(review: SolanaRequestReview): String {
    val message = review.messageBytes
    if (message != null) {
        return if (printableText(message) != null) "Signs a message as text." else "Signs a message as bytes."
    }
    (review.instructions.singleOrNull() as? SolanaInstruction.CreateNonceAccount)?.let {
        return "Create a nonce account ${solanaAccountText(it.account)}, rent ${lamportsText(it.lamports)} SOL, " +
            "authority ${solanaAccountText(it.authority)}."
    }
    val transfers = review.instructions.filter {
        it is SolanaInstruction.SystemTransfer || it is SolanaInstruction.TokenTransfer || it is SolanaInstruction.TokenTransferChecked
    }
    return when (val only = transfers.singleOrNull()) {
        is SolanaInstruction.SystemTransfer -> "Sends ${lamportsText(only.lamports)} SOL to ${solanaAccountText(only.to)}."
        is SolanaInstruction.TokenTransferChecked ->
            "Sends ${tokenAmountText(only.amount, only.decimals, review.tokenOf(only.mint))} to token account " +
                "${solanaAccountText(only.destination)}."
        is SolanaInstruction.TokenTransfer -> "Sends ${only.amount} base units to token account ${solanaAccountText(only.destination)}."
        else -> "Signs a transaction with ${messageInstructionCount(review)} instructions."
    }
}

/**
 * The short facts under the sentence: the instruction count, the fee payer, the priority fee when the transaction sets one, and the mint
 * when a single mint is named. The fee payer and the mint are shortened here, as the sentence shortens addresses. The details hold each
 * whole address.
 */
fun solanaFacts(review: SolanaRequestReview): List<Pair<String, String>> {
    val feePayer = review.feePayer ?: return emptyList()
    return buildList {
        add("Instructions" to messageInstructionCount(review).toString())
        add(FEE_PAYER to shortAddress(feePayer))
        review.priorityFeeLamports?.let { add(PRIORITY_FEE to "$it lamports") }
        review.mints.singleOrNull()?.let { add(MINT to mintFactText(it)) }
    }
}

/** How many instructions the message holds. A nonce setup is two of them and one decoded instruction, so this counts the message. */
private fun messageInstructionCount(review: SolanaRequestReview): Int =
    review.message?.instructions?.size ?: review.instructions.size

private fun mintFactText(mint: SolanaMessageAccount): String = when (mint) {
    is SolanaMessageAccount.Static -> shortAddress(mint.address)
    is SolanaMessageAccount.Lookup -> solanaAccountText(mint)
}

/** The text of each warning the review shows, in the order the review holds them. */
fun solanaWarningText(warning: SolanaWarning): String = when (warning) {
    SolanaWarning.UNKNOWN_PROGRAM ->
        "Unknown program. An instruction calls a program that Lensly does not read, so Lensly cannot say what it does. " +
            "Its raw bytes are shown below."
    SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER ->
        "Nonce authority is not this account. Only the authority can advance a durable nonce, and this account is not the authority."
    SolanaWarning.LOOKUP_TABLE ->
        "Some accounts come from address lookup tables. Lensly cannot check those addresses offline."
    SolanaWarning.UNKNOWN_TOKEN ->
        "Unknown token. This mint is not in Lensly's token list, so Lensly shows its address and cannot name the token."
    SolanaWarning.DECIMALS_UNKNOWN ->
        "Decimals unknown. A plain token Transfer does not name its mint, so its amount is shown in base units."
}
