package app.lensly.app.sign

import app.airvault.chains.solana.signer.SolanaMessageAccount
import app.airvault.core.model.printableText
import app.lensly.app.addressesIn
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaInstruction
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.solana.SolanaToken
import app.lensly.core.solana.SolanaWarning
import app.lensly.ui.sign.NOT_STATED
import app.lensly.ui.sign.ReviewFact
import app.lensly.ui.sign.shortAddress
import app.lensly.ui.sign.solanaClusterName
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The label of the signing account's row. */
private const val SIGNER = "Signer"

/** The label of the fee payer's row. */
private const val FEE_PAYER = "Fee Payer"

/** The label of the row that names an instruction's program by its ID. */
private const val PROGRAM = "Program"

/** The label of the network row in the header of a Solana review. */
private const val NETWORK = "Network"

/** The label of the application domain, in the rows of an off-chain message. */
private const val APPLICATION_DOMAIN = "Application Domain"

/** The label of the message text, in the rows of an off-chain message. */
private const val MESSAGE = "Message"

/** The label of the priority fee, in the facts under the sentence. */
private const val PRIORITY_FEE = "Priority Fee"

/** The label of the base fee, in the facts under the sentence. */
private const val BASE_FEE = "Base Fee"

/** The label of the most the transaction can cost in fees, in the facts under the sentence. */
private const val MAX_FEE = "Max Fee"

/** The label of the recent blockhash, which a durable nonce stores in its place. */
private const val RECENT_BLOCKHASH = "Recent Blockhash"

/** The label of a required signer that is neither this account nor the fee payer. */
private const val OTHER_SIGNER = "Other Required Signer"

/** The label of a program Lensly does not know at all. */
private const val UNKNOWN_PROGRAM = "Unknown Program"

/** The label of a program Lensly reads in other instructions, for an instruction it does not read. */
private const val NOT_DECODED = "Program not decoded: instruction is not read"

/** The label of the mint, in the facts under the sentence, when one mint is named. */
private const val MINT = "Mint"

/** The decimal places of a lamport: one SOL is 10^9 lamports. */
private const val LAMPORT_DECIMALS = 9

/**
 * The label and value rows a Solana review shows, in order. A transaction gives the signer, the fee payer, any other required signer, the
 * recent blockhash and then each instruction, with its accounts. An off-chain message gives the signer, the application domain and the
 * message text, whole. The review screen and the Review Record both show these rows, so they say the same thing.
 */
fun solanaRows(review: SolanaRequestReview): List<Pair<String, String>> = buildList {
    add(SIGNER to review.signerAddress)
    val message = review.offchainMessage
    val feePayer = review.feePayer
    if (message != null) {
        add(APPLICATION_DOMAIN to SolanaAccount.addressOf(message.applicationDomain))
        add(MESSAGE to message.text)
    } else if (feePayer != null) {
        add(FEE_PAYER to feePayer)
        review.otherRequiredSigners.forEach { add(OTHER_SIGNER to it) }
        review.recentBlockhash?.let { add(RECENT_BLOCKHASH to it) }
        review.instructions.forEach { addAll(instructionRows(it, review)) }
    }
}

/**
 * Whether the owner ticks, before the review signs, that Lensly cannot say what an instruction does. That is so when an instruction is one
 * Lensly does not read, from an unknown program or from a known one.
 */
fun solanaNeedsUnderstanding(review: SolanaRequestReview): Boolean = SolanaWarning.UNKNOWN_PROGRAM in review.warnings

/**
 * The network row of a Solana review, for the header: the cluster that Lumio states, marked as stated by Lumio, or "Not stated" when the
 * request names none. The header itself names Solana, which every Solana request is, so this row is where the cluster shows.
 */
internal fun solanaNetworkFact(review: SolanaRequestReview): ReviewFact {
    val value = review.cluster?.let { "${solanaClusterName(it)} (stated by Lumio)" } ?: NOT_STATED
    return ReviewFact(label = NETWORK, value = value, mono = false)
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
        "SOL Transfer" to "${lamportsText(instruction.lamports)} SOL",
        "From" to solanaAccountText(instruction.from),
        "To" to solanaAccountText(instruction.to),
    )
    // A plain Transfer names no mint, so its amount is in base units and its decimals are not known.
    is SolanaInstruction.TokenTransfer -> listOf(
        "Token Transfer" to "${instruction.amount} base units, decimals unknown",
        "From Token Account" to solanaAccountText(instruction.source),
        "To Token Account" to solanaAccountText(instruction.destination),
        "Authority" to solanaAccountText(instruction.authority),
    )
    is SolanaInstruction.TokenTransferChecked -> {
        val token = review.tokenOf(instruction.mint)
        listOf(
            "Token Transfer" to tokenAmountText(instruction.amount, instruction.decimals, token),
            "Token" to tokenNameText(token),
            "Mint" to solanaAccountText(instruction.mint),
            "From Token Account" to solanaAccountText(instruction.source),
            "To Token Account" to solanaAccountText(instruction.destination),
            "Authority" to solanaAccountText(instruction.authority),
        )
    }
    // The owner is the wallet the token account is made for. The token account is the one the program creates.
    is SolanaInstruction.AssociatedTokenCreate -> {
        val token = review.tokenOf(instruction.mint)
        listOf(
            "Create Token Account" to if (instruction.idempotent) "only if missing" else "fails if it exists",
            "Owner" to solanaAccountText(instruction.owner),
            "Token" to tokenNameText(token),
            "Mint" to solanaAccountText(instruction.mint),
            "Token Account" to solanaAccountText(instruction.account),
            "Paid by" to solanaAccountText(instruction.payer),
        )
    }
    is SolanaInstruction.AdvanceNonce -> listOf(
        "Uses Durable Nonce Account" to solanaAccountText(instruction.nonceAccount),
        "Nonce Authority" to solanaAccountText(instruction.authority),
    )
    is SolanaInstruction.CreateNonceAccount -> nonceSetupRows(instruction)
    is SolanaInstruction.SetComputeUnitLimit -> listOf("Compute Limit" to "${instruction.units} units")
    is SolanaInstruction.SetComputeUnitPrice ->
        listOf("Compute Price" to "${instruction.microLamports} micro-lamports per unit")
    is SolanaInstruction.Raw -> rawRows(instruction, review)
}

/**
 * The rows of an instruction Lensly does not read: its program, its data, and each account with the roles the message gives it, so the
 * owner sees which accounts sign and which are writable. A program Lensly reads in other instructions is not called unknown.
 */
private fun rawRows(raw: SolanaInstruction.Raw, review: SolanaRequestReview): List<Pair<String, String>> = buildList {
    add((if (raw.knownProgram) NOT_DECODED else UNKNOWN_PROGRAM) to solanaAccountText(raw.programId))
    add("Instruction Data" to "0x" + raw.dataHex)
    if (raw.accounts.isEmpty()) add("Accounts" to "none")
    raw.accounts.zip(raw.accountIndices).forEach { (account, index) ->
        val roles = review.message?.roles(index)
        add((if (roles == null) "Account" else "Account ($roles)") to solanaAccountText(account))
    }
}

/**
 * The rows of a nonce setup: the account it creates, and when the account is derived from a seed, the base and the seed. Then its rent,
 * its authority, and who pays for it.
 */
private fun nonceSetupRows(instruction: SolanaInstruction.CreateNonceAccount): List<Pair<String, String>> = buildList {
    add("Create a Nonce Account" to solanaAccountText(instruction.account))
    instruction.seed?.let {
        add("Base Account" to solanaAccountText(it.base))
        add("Seed" to it.text)
    }
    add("Rent" to "${lamportsText(instruction.lamports)} SOL")
    add("Nonce Authority" to solanaAccountText(instruction.authority))
    add("Paid by" to solanaAccountText(instruction.funder))
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
    if (review.offchainMessage != null) return "Signs a message as text."
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
 * The short facts under the sentence: the instruction count, the fee payer, the base, priority and most fee in lamports, and the mint when
 * a single mint is named. A priority fee and a maximum fee that rest on the default compute limit say "up to", since the default is an
 * upper bound. The fee payer and the mint are shortened here, as the sentence shortens addresses. The details hold each whole address.
 */
fun solanaFacts(review: SolanaRequestReview): List<Pair<String, String>> {
    val feePayer = review.feePayer ?: return emptyList()
    val bound = if (review.priorityFeeIsBound) "up to " else ""
    return buildList {
        add("Instructions" to messageInstructionCount(review).toString())
        add(FEE_PAYER to shortAddress(feePayer))
        add(BASE_FEE to "${review.baseFeeLamports} lamports")
        add(PRIORITY_FEE to "$bound${review.priorityFeeLamports} lamports")
        add(MAX_FEE to "$bound${review.maxFeeLamports} lamports")
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
        "Unknown or undecoded instruction. Lensly does not read it, so Lensly cannot say what it does. Its raw bytes are shown below."
    SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER ->
        "Nonce authority is not this account. Only the authority can advance a durable nonce, and this account is not the authority."
    SolanaWarning.DURABLE_NONCE ->
        "Durable nonce. This transaction has no expiry. It stays valid until the nonce advances, and advancing the nonce cancels it."
    SolanaWarning.LOOKUP_TABLE ->
        "Some accounts come from address lookup tables. Lensly cannot check those addresses offline."
    SolanaWarning.UNKNOWN_TOKEN ->
        "Unknown token. This mint is not in Lensly's token list, so Lensly shows its address and cannot name the token."
    SolanaWarning.DECIMALS_UNKNOWN ->
        "Decimals unknown. A plain token Transfer does not name its mint, so its amount is shown in base units."
}
