package app.airvault.chains.solana.signer

import app.airvault.chains.solana.SOLANA_DEVNET
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.chains.solana.SOLANA_TESTNET
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.FieldPath
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.airvault.core.model.checked
import fr.acinq.bitcoin.Base58
import java.math.BigDecimal

/** The label the owner ticks for an instruction Lensly does not read. Sentence case, beside its checkbox. */
private const val UNDERSTAND = "I understand Lensly cannot say what this does."

/** The label the owner ticks for an instruction that hands control to another address. Sentence case, beside its checkbox. */
private const val ACK_CONTROL = "I understand this gives another address control."

/** The label the owner ticks for a durable nonce whose authority is not this account. Sentence case, beside its checkbox. */
private const val ACK_NONCE_AUTHORITY = "I understand the nonce authority is not this account."

/** The longest address shown whole in a fact. A longer one is shortened to its first and last characters. */
private const val SHORT_ADDRESS_LIMIT = 16

/** The decimal places of a lamport: one SOL is 10^9 lamports. */
private const val LAMPORT_DECIMALS = 9

/** The note under the origin the wallet names. The origin is the wallet's word, so the review says Lensly cannot check it. */
private const val ORIGIN_NOTE = "Named by the wallet. Lensly cannot verify it."

/** The label of the origin a request names. The request sets it, so the review does not vouch for it. Same label as the EVM review. */
private const val CLAIMED_ORIGIN_LABEL = "Claimed by Request (Not Verified)"

/** The decoded fields of a Solana request, as the review names them. */
private object Fields {
    val signer = FieldPath("solana.signer")
    val origin = FieldPath("solana.origin")
    val cluster = FieldPath("solana.cluster")
    val applicationDomain = FieldPath("solana.applicationDomain")
    val text = FieldPath("solana.text")
    val feePayer = FieldPath("solana.feePayer")
    val otherSigner = FieldPath("solana.otherSigner")
    val unusedAccount = FieldPath("solana.unusedAccount")
    val format = FieldPath("solana.format")
    val recentBlockhash = FieldPath("solana.recentBlockhash")
    val numRequiredSignatures = FieldPath("solana.numRequiredSignatures")
    val instructions = FieldPath("solana.instructions")
    val maxFee = FieldPath("solana.maxFee")
    val program = FieldPath("solana.program")
    val from = FieldPath("solana.from")
    val to = FieldPath("solana.to")
    val lamports = FieldPath("solana.lamports")
    val source = FieldPath("solana.source")
    val destination = FieldPath("solana.destination")
    val authority = FieldPath("solana.authority")
    val amount = FieldPath("solana.amount")
    val decimals = FieldPath("solana.decimals")
    val mint = FieldPath("solana.mint")
    val owner = FieldPath("solana.owner")
    val payer = FieldPath("solana.payer")
    val account = FieldPath("solana.account")
    val idempotent = FieldPath("solana.idempotent")
    val nonceAccount = FieldPath("solana.nonceAccount")
    val funder = FieldPath("solana.funder")
    val seedBase = FieldPath("solana.seedBase")
    val seedText = FieldPath("solana.seedText")
    val units = FieldPath("solana.computeUnitLimit")
    val microLamports = FieldPath("solana.computeUnitPrice")
    val heapBytes = FieldPath("solana.heapSize")
    val dataLimitBytes = FieldPath("solana.accountDataLimit")
    val delegate = FieldPath("solana.delegate")
    val newAuthority = FieldPath("solana.newAuthority")
    val authorityType = FieldPath("solana.authorityType")
    val space = FieldPath("solana.space")
    val data = FieldPath("solana.data")
    val accounts = FieldPath("solana.accounts")
}

/**
 * The review of this request (spec 3.2), with the coverage check applied: a field the parser read that no row shows is a BLOCK. The header,
 * the sentence, the facts, the rows and the warnings are the ones the Solana review has always shown.
 */
fun SolanaRequestReview.toReview(): Review {
    val chain = clusterChain(cluster)
    return Review(
        title = "Review",
        chain = chain,
        // A message commits to no cluster, so the cluster the request states is shown only in its own row, never as a testnet mark.
        network = NetworkLabel(name = "Solana", testnet = false, detail = null, row = clusterRow()),
        summary = listOf(sentence()),
        facts = facts(),
        sections = listOf(ReviewSection(if (message == null) "Message" else "Instructions", rows(chain))),
        warnings = warnings.map(::warningOf),
        coverage = Coverage(decoded = decodedFields(), excluded = excludedFields()),
    ).checked()
}

/** The text of each warning the review shows, in the order the review holds them. */
fun solanaWarningText(warning: SolanaWarning): String = when (warning) {
    SolanaWarning.UNKNOWN_PROGRAM ->
        "Unknown or undecoded instruction. Lensly does not read it, so Lensly cannot say what it does. Its raw bytes are shown below."
    SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER ->
        "Nonce authority is not this account. Only the authority can advance a durable nonce, and this account is not the authority. " +
            "For a new nonce account, the authority can also withdraw all of its deposit."
    SolanaWarning.DURABLE_NONCE ->
        "Durable nonce. This transaction has no expiry. It stays valid until the nonce advances, and advancing the nonce cancels it."
    SolanaWarning.LOOKUP_TABLE ->
        "Some accounts come from address lookup tables. Lensly cannot check those addresses offline."
    SolanaWarning.UNKNOWN_TOKEN ->
        "Unknown token. This mint is not in Lensly's token list, so Lensly shows its address and cannot name the token."
    SolanaWarning.DECIMALS_UNKNOWN ->
        "Decimals unknown. A plain token Transfer does not name its mint, so its amount is shown in base units."
    SolanaWarning.TOKEN_2022 ->
        "Token-2022 token. Its mint may carry extensions that Lensly cannot read offline. A transfer fee means the recipient can get " +
            "less than the amount shown. A transfer hook runs another program on each transfer. A permanent delegate can move the tokens later."
    SolanaWarning.HANDS_OVER_CONTROL ->
        "This gives another address control. An approval lets its delegate spend the tokens, and an authority change lets the new authority " +
            "act as the owner. Check that you trust the address shown."
    SolanaWarning.NEW_ACCOUNT_OWNER ->
        "New account for another program. The account is given to a program that is not the System Program or a token program. That " +
            "program decides what the deposit can do. Check the owner program shown."
    SolanaWarning.CLOSE_TO_OTHER_ADDRESS ->
        "Remaining SOL goes to another address. Closing the token account sends its remaining SOL to an address that is not this account. " +
            "Check the address shown."
}

/** The chain a cluster is: mainnet-beta when the request states none, as Lensly's Solana family is. */
private fun clusterChain(cluster: SolCluster?): ChainId = when (cluster) {
    SolCluster.DEVNET -> SOLANA_DEVNET
    SolCluster.TESTNET -> SOLANA_TESTNET
    SolCluster.MAINNET_BETA, null -> SOLANA_MAINNET
}

/** The network row of the header: the cluster that Lumio states, marked as stated by Lumio, or "Not stated" when the request names none. */
private fun SolanaRequestReview.clusterRow(): ReviewRow {
    val value = cluster?.let { "${clusterName(it)} (stated by Lumio)" } ?: "Not stated"
    return ReviewRow("Network", DisplayValue.Text(value), Fields.cluster)
}

/** The name of a cluster a request states. Mainnet-beta is named as the mainnet, and devnet and testnet as the test networks. */
private fun clusterName(cluster: SolCluster): String = when (cluster) {
    SolCluster.MAINNET_BETA -> "Solana Mainnet"
    SolCluster.DEVNET -> "Solana Devnet"
    SolCluster.TESTNET -> "Solana Testnet"
}

/**
 * The summary at the top of the review. It is a [SummaryPart.Send] when there is exactly one transfer, in SOL or in a token, sent from its
 * authority to its receiving account. Otherwise it says what is signed.
 */
private fun SolanaRequestReview.sentence(): SummaryPart {
    if (offchainMessage != null) return SummaryPart.Text("Signs a message as text.")
    // A send is named only when it is the one instruction that moves value or control, so no deposit or approval hides behind it. An
    // instruction Lensly does not read could do anything, so with one beside it the headline stays neutral.
    val only = if (instructions.any { it is SolanaInstruction.Raw }) null else instructions.filter { it.movesValueOrControl }.singleOrNull()
    return when (only) {
        is SolanaInstruction.CreateNonceAccount -> SummaryPart.Text(
            "Create a nonce account ${accountText(only.account)}, deposit ${lamportsText(only.lamports)} SOL, " +
                "authority ${accountText(only.authority)}.",
        )
        is SolanaInstruction.SystemTransfer ->
            SummaryPart.Send(lamportsText(only.lamports), "SOL", accountText(only.from), accountText(only.to))
        is SolanaInstruction.TokenTransferChecked -> SummaryPart.Send(
            decimalText(only.amount, only.decimals),
            tokenOf(only.mint)?.symbol ?: "of an unknown token",
            accountText(only.authority),
            accountText(only.destination),
        )
        is SolanaInstruction.TokenTransfer ->
            SummaryPart.Send("${only.amount}", "base units", accountText(only.authority), accountText(only.destination))
        else -> messageInstructionCount().let { SummaryPart.Text("Signs a transaction with $it ${if (it == 1) "instruction" else "instructions"}.") }
    }
}

/** Whether this instruction moves SOL or tokens, or hands control of an account over. A decoded instruction that does neither is not one. */
private val SolanaInstruction.movesValueOrControl: Boolean
    get() = this !is SolanaInstruction.Raw && this !is SolanaInstruction.AdvanceNonce && this !is SolanaInstruction.SetComputeUnitLimit &&
        this !is SolanaInstruction.SetComputeUnitPrice && this !is SolanaInstruction.SetHeapSize &&
        this !is SolanaInstruction.SetAccountDataLimit && this !is SolanaInstruction.AssociatedTokenCreate

/**
 * The short facts under the sentence: the instruction count, the fee payer, the base, priority and most fee in lamports, and the mint when a
 * single mint is named. A priority fee and a maximum fee that rest on the default compute limit say "up to", since the default is an upper
 * bound. The fee payer and the mint are shortened here, as the sentence shortens addresses.
 */
private fun SolanaRequestReview.facts(): List<ReviewRow> {
    val feePayer = feePayer ?: return emptyList()
    val bound = if (priorityFeeIsBound) "up to " else ""
    return buildList {
        add(ReviewRow("Instructions", DisplayValue.Text(messageInstructionCount().toString()), Fields.instructions))
        add(ReviewRow("Fee Payer", DisplayValue.Text(shortAddress(feePayer), mono = true), Fields.feePayer))
        add(ReviewRow("Base Fee", DisplayValue.Text("$baseFeeLamports lamports", mono = true), Fields.numRequiredSignatures))
        add(ReviewRow("Priority Fee", DisplayValue.Text("$bound$priorityFeeLamports lamports", mono = true), Fields.microLamports))
        add(ReviewRow("Max Fee", DisplayValue.Text("$bound$maxFeeLamports lamports", mono = true), Fields.maxFee))
        mints.singleOrNull()?.let { add(ReviewRow("Mint", DisplayValue.Text(mintText(it), mono = true), Fields.mint)) }
    }
}

/** How many instructions the message holds. A nonce setup is two of them and one decoded instruction, so this counts the message. */
private fun SolanaRequestReview.messageInstructionCount(): Int = message?.instructions?.size ?: instructions.size

/** The rows of the one section: the signer, the origin the wallet names, then for a message its other signers, application domain, format and
 * text, and for a transaction its fee payer, other required signers, unused accounts, recent blockhash and each instruction with its accounts. */
private fun SolanaRequestReview.rows(chain: ChainId): List<ReviewRow> = buildList {
    add(ReviewRow("Signer", DisplayValue.Address(chain, signerAddress), Fields.signer))
    origin?.let { add(ReviewRow(CLAIMED_ORIGIN_LABEL, DisplayValue.Text(it), Fields.origin, notes = listOf(ORIGIN_NOTE))) }
    val offchain = offchainMessage
    val tx = message
    if (offchain != null) {
        offchainOtherSigners(offchain).forEach {
            add(ReviewRow("Other Signer", DisplayValue.Address(chain, Base58.encode(it)), Fields.otherSigner))
        }
        add(ReviewRow("Application Domain", DisplayValue.Address(chain, Base58.encode(offchain.applicationDomain)), Fields.applicationDomain))
        add(ReviewRow("Format", DisplayValue.Text(formatText(offchain.format)), Fields.format))
        add(ReviewRow("Message", DisplayValue.Text(offchain.text), Fields.text))
    } else if (tx != null) {
        add(ReviewRow("Fee Payer", DisplayValue.Address(chain, tx.feePayer), Fields.feePayer))
        otherRequiredSigners.forEach { add(ReviewRow("Other Required Signer", DisplayValue.Address(chain, it), Fields.otherSigner)) }
        unusedAccounts.forEach {
            add(accountRow(if (it is SolanaMessageAccount.Lookup) "Unused Lookup Account" else "Unused Account", chain, it, Fields.unusedAccount))
        }
        add(ReviewRow("Recent Blockhash", DisplayValue.Text(Base58.encode(tx.recentBlockhash)), Fields.recentBlockhash))
        instructions.forEach { addAll(instructionRows(it, chain)) }
    }
}

/** The rows one instruction shows. A known instruction names its program first, by its ID. A raw instruction names its program in its own rows. */
private fun SolanaRequestReview.instructionRows(instruction: SolanaInstruction, chain: ChainId): List<ReviewRow> {
    val details = detailRows(instruction, chain)
    if (instruction is SolanaInstruction.Raw) return details
    return listOf(accountRow("Program", chain, instruction.programId, Fields.program)) + details
}

private fun SolanaRequestReview.detailRows(instruction: SolanaInstruction, chain: ChainId): List<ReviewRow> = when (instruction) {
    is SolanaInstruction.SystemTransfer -> listOf(
        textRow("SOL Transfer", "${lamportsText(instruction.lamports)} SOL", Fields.lamports),
        accountRow("From", chain, instruction.from, Fields.from),
        accountRow("To", chain, instruction.to, Fields.to),
    )
    // A plain Transfer names no mint, so its amount is in base units and its decimals are not known.
    is SolanaInstruction.TokenTransfer -> listOf(
        textRow("Token Transfer", "${instruction.amount} base units, decimals unknown", Fields.amount),
        accountRow("From Token Account", chain, instruction.source, Fields.source),
        accountRow("To Token Account", chain, instruction.destination, Fields.destination),
        accountRow("Authority", chain, instruction.authority, Fields.authority),
    )
    is SolanaInstruction.TokenTransferChecked -> {
        val token = tokenOf(instruction.mint)
        listOf(
            textRow("Token Transfer", tokenAmountText(instruction.amount, instruction.decimals, token), Fields.amount),
            textRow("Token", tokenNameText(token), Fields.mint),
            accountRow("Mint", chain, instruction.mint, Fields.mint),
            accountRow("From Token Account", chain, instruction.source, Fields.source),
            accountRow("To Token Account", chain, instruction.destination, Fields.destination),
            accountRow("Authority", chain, instruction.authority, Fields.authority),
        )
    }
    // The owner is the wallet the token account is made for. The token account is the one the program creates.
    is SolanaInstruction.AssociatedTokenCreate -> {
        val token = tokenOf(instruction.mint)
        listOf(
            textRow("Create Token Account", if (instruction.idempotent) "only if missing" else "fails if it exists", Fields.idempotent),
            accountRow("Owner", chain, instruction.owner, Fields.owner),
            textRow("Token", tokenNameText(token), Fields.mint),
            accountRow("Mint", chain, instruction.mint, Fields.mint),
            accountRow("Token Account", chain, instruction.account, Fields.account),
            accountRow("Paid by", chain, instruction.payer, Fields.payer),
        )
    }
    is SolanaInstruction.AdvanceNonce -> listOf(
        accountRow("Uses Durable Nonce Account", chain, instruction.nonceAccount, Fields.nonceAccount),
        accountRow("Nonce Authority", chain, instruction.authority, Fields.authority),
    )
    is SolanaInstruction.CreateNonceAccount -> nonceSetupRows(instruction, chain)
    is SolanaInstruction.TokenApprove -> buildList {
        val token = instruction.mint?.let(::tokenOf)
        add(textRow("Token Approval", approvalAmountText(instruction, token), Fields.amount))
        add(accountRow("Delegate", chain, instruction.delegate, Fields.delegate))
        if (instruction.mint != null) {
            add(textRow("Token", tokenNameText(token), Fields.mint))
            add(accountRow("Mint", chain, instruction.mint, Fields.mint))
        }
        add(accountRow("Token Account", chain, instruction.source, Fields.source))
        add(accountRow("Owner", chain, instruction.owner, Fields.owner))
    }
    is SolanaInstruction.TokenSetAuthority -> listOf(
        textRow("Change Authority", authorityTypeText(instruction.authorityType), Fields.authorityType),
        accountRow("Token Account", chain, instruction.account, Fields.account),
        instruction.newAuthority?.let { accountRow("New Authority", chain, it, Fields.newAuthority) }
            ?: textRow("New Authority", "None (the authority is removed for good)", Fields.newAuthority),
        accountRow("Current Authority", chain, instruction.currentAuthority, Fields.authority),
    )
    is SolanaInstruction.TokenCloseAccount -> listOf(
        accountRow("Close Token Account", chain, instruction.account, Fields.account),
        accountRow("Remaining SOL To", chain, instruction.destination, Fields.destination),
        accountRow("Owner", chain, instruction.owner, Fields.owner),
    )
    is SolanaInstruction.TokenBurn -> {
        val token = tokenOf(instruction.mint)
        listOf(
            textRow(
                "Token Burn",
                instruction.decimals?.let { tokenAmountText(instruction.amount, it, token) } ?: "${instruction.amount} base units, decimals unknown",
                Fields.amount,
            ),
            textRow("Token", tokenNameText(token), Fields.mint),
            accountRow("Mint", chain, instruction.mint, Fields.mint),
            accountRow("From Token Account", chain, instruction.account, Fields.account),
            accountRow("Authority", chain, instruction.authority, Fields.authority),
        )
    }
    is SolanaInstruction.SystemCreateAccount -> listOf(
        accountRow("Create Account", chain, instruction.account, Fields.account),
        textRow("Deposit", "${lamportsText(instruction.lamports)} SOL", Fields.lamports),
        textRow("Space", "${instruction.space} bytes", Fields.space),
        accountRow("Owner Program", chain, SolanaMessageAccount.Static(instruction.owner), Fields.owner),
        accountRow("Paid by", chain, instruction.funder, Fields.funder),
    )
    is SolanaInstruction.SystemAssign -> listOf(
        accountRow("Assign Account", chain, instruction.account, Fields.account),
        accountRow("New Owner Program", chain, SolanaMessageAccount.Static(instruction.owner), Fields.owner),
    )
    is SolanaInstruction.WithdrawNonce -> listOf(
        textRow("Nonce Withdrawal", "${lamportsText(instruction.lamports)} SOL", Fields.lamports),
        accountRow("From Nonce Account", chain, instruction.nonceAccount, Fields.nonceAccount),
        accountRow("To", chain, instruction.to, Fields.to),
        accountRow("Nonce Authority", chain, instruction.authority, Fields.authority),
    )
    is SolanaInstruction.AuthorizeNonce -> listOf(
        accountRow("Change Nonce Authority", chain, instruction.nonceAccount, Fields.nonceAccount),
        accountRow("New Nonce Authority", chain, instruction.newAuthority, Fields.newAuthority),
        accountRow("Current Nonce Authority", chain, instruction.currentAuthority, Fields.authority),
    )
    is SolanaInstruction.SetComputeUnitLimit -> listOf(textRow("Compute Limit", "${instruction.units} units", Fields.units))
    is SolanaInstruction.SetComputeUnitPrice ->
        listOf(textRow("Compute Price", "${instruction.microLamports} micro-lamports per unit", Fields.microLamports))
    is SolanaInstruction.SetHeapSize -> listOf(textRow("Heap Size", "${instruction.bytes} bytes", Fields.heapBytes))
    is SolanaInstruction.SetAccountDataLimit -> listOf(textRow("Account Data Limit", "${instruction.bytes} bytes", Fields.dataLimitBytes))
    is SolanaInstruction.Raw -> rawRows(instruction, chain)
}

/**
 * The rows of an instruction Lensly does not read: its program, its data, and each account with the roles the message gives it, so the
 * owner sees which accounts sign and which are writable. A program Lensly reads in other instructions is not called unknown.
 */
private fun SolanaRequestReview.rawRows(raw: SolanaInstruction.Raw, chain: ChainId): List<ReviewRow> = buildList {
    add(accountRow(if (raw.knownProgram) NOT_DECODED else "Unknown Program", chain, raw.programId, Fields.program))
    add(textRow("Instruction Data", "0x" + raw.dataHex, Fields.data))
    if (raw.accounts.isEmpty()) add(textRow("Accounts", "none", Fields.accounts))
    raw.accounts.zip(raw.accountIndices).forEach { (account, index) ->
        val roles = message?.roles(index)
        add(accountRow(if (roles == null) "Account" else "Account ($roles)", chain, account, Fields.accounts))
    }
}

/** The label of a program Lensly reads in other instructions, for an instruction it does not read. */
private const val NOT_DECODED = "Program not decoded: instruction is not read"

/**
 * The rows of a nonce setup: the account it creates, and when the account is derived from a seed, the base and the seed. Then its deposit,
 * its authority, and who pays for it. The deposit is not rent: the authority can withdraw all of it.
 */
private fun nonceSetupRows(instruction: SolanaInstruction.CreateNonceAccount, chain: ChainId): List<ReviewRow> = buildList {
    add(accountRow("Create a Nonce Account", chain, instruction.account, Fields.account))
    instruction.seed?.let {
        add(accountRow("Base Account", chain, it.base, Fields.seedBase))
        add(textRow("Seed", it.text, Fields.seedText))
    }
    add(textRow("Deposit", "${lamportsText(instruction.lamports)} SOL", Fields.lamports))
    add(accountRow("Nonce Authority", chain, instruction.authority, Fields.authority))
    add(accountRow("Paid by", chain, instruction.funder, Fields.funder))
}

/** The amount an approval lets the delegate spend. An unchecked approval names no mint, so its amount is in base units. */
private fun approvalAmountText(approval: SolanaInstruction.TokenApprove, token: SolanaToken?): String =
    approval.decimals?.let { tokenAmountText(approval.amount, it, token) } ?: "${approval.amount} base units, decimals unknown"

/** The authority an SPL Token SetAuthority changes. Token-2022 adds more types, which are shown by number. */
private fun authorityTypeText(type: Int): String = when (type) {
    0 -> "Mint tokens"
    1 -> "Freeze account"
    2 -> "Account owner"
    3 -> "Close account"
    else -> "Authority type $type"
}

private fun textRow(label: String, value: String, field: FieldPath) = ReviewRow(label, DisplayValue.Text(value), field)

private fun accountRow(label: String, chain: ChainId, account: SolanaMessageAccount, field: FieldPath) =
    ReviewRow(label, accountValue(chain, account), field)

/** An account as a value: its base58 address, or for an account from a lookup table, the table and index, which Lensly cannot check offline. */
private fun accountValue(chain: ChainId, account: SolanaMessageAccount): DisplayValue = when (account) {
    is SolanaMessageAccount.Static -> DisplayValue.Address(chain, account.address)
    is SolanaMessageAccount.Lookup -> DisplayValue.Text(accountText(account))
}

/** The text an account shows as: its base58 address. A lookup-table account shows the table, the index and the note that Lensly cannot check it offline. */
private fun accountText(account: SolanaMessageAccount): String = when (account) {
    is SolanaMessageAccount.Static -> account.address
    is SolanaMessageAccount.Lookup ->
        "lookup table ${account.tableAddress}, index ${account.tableIndex} (can't be checked offline)"
}

private fun mintText(mint: SolanaMessageAccount): String = when (mint) {
    is SolanaMessageAccount.Static -> shortAddress(mint.address)
    is SolanaMessageAccount.Lookup -> accountText(mint)
}

/** An address shortened to its first and last characters, for a fact with no room for the full one. */
private fun shortAddress(address: String): String =
    if (address.length <= SHORT_ADDRESS_LIMIT) address else "${address.take(10)}...${address.takeLast(6)}"

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

private fun warningOf(warning: SolanaWarning): Warning = when (warning) {
    SolanaWarning.UNKNOWN_PROGRAM ->
        Warning(Danger.DANGER, "solana.unknown-program", solanaWarningText(warning), acknowledge = UNDERSTAND)
    SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER ->
        Warning(Danger.DANGER, "solana.nonce-authority", solanaWarningText(warning), acknowledge = ACK_NONCE_AUTHORITY)
    SolanaWarning.DURABLE_NONCE -> Warning(Danger.INFO, "solana.durable-nonce", solanaWarningText(warning))
    SolanaWarning.LOOKUP_TABLE -> Warning(Danger.CAUTION, "solana.lookup-table", solanaWarningText(warning))
    SolanaWarning.UNKNOWN_TOKEN -> Warning(Danger.CAUTION, "solana.unknown-token", solanaWarningText(warning))
    SolanaWarning.DECIMALS_UNKNOWN -> Warning(Danger.CAUTION, "solana.decimals-unknown", solanaWarningText(warning))
    SolanaWarning.TOKEN_2022 -> Warning(Danger.CAUTION, "solana.token-2022", solanaWarningText(warning))
    SolanaWarning.HANDS_OVER_CONTROL ->
        Warning(Danger.DANGER, "solana.hands-over-control", solanaWarningText(warning), acknowledge = ACK_CONTROL)
    SolanaWarning.NEW_ACCOUNT_OWNER -> Warning(Danger.CAUTION, "solana.new-account-owner", solanaWarningText(warning))
    SolanaWarning.CLOSE_TO_OTHER_ADDRESS -> Warning(Danger.CAUTION, "solana.close-to-other-address", solanaWarningText(warning))
}

/** The signers of an off-chain message other than the signing account, in the order the message gives them. */
private fun SolanaRequestReview.offchainOtherSigners(offchain: SolanaOffchainMessage): List<ByteArray> =
    offchain.signers.filterNot { it.contentEquals(signer) }

/** The format of an off-chain message, as the review names it. */
private fun formatText(format: SolanaOffchainMessage.Format): String = when (format) {
    SolanaOffchainMessage.Format.RESTRICTED_ASCII -> "Restricted ASCII"
    SolanaOffchainMessage.Format.LIMITED_UTF8 -> "Limited UTF-8"
}

/** The fields the parser read from this request. A row that shows a field accounts for it. */
private fun SolanaRequestReview.decodedFields(): Set<FieldPath> = buildSet {
    add(Fields.signer)
    origin?.let { add(Fields.origin) }
    add(Fields.cluster)
    offchainMessage?.let { offchain ->
        add(Fields.applicationDomain)
        add(Fields.format)
        add(Fields.text)
        if (offchainOtherSigners(offchain).isNotEmpty()) add(Fields.otherSigner)
    }
    if (message != null) {
        add(Fields.feePayer)
        add(Fields.numRequiredSignatures)
        add(Fields.recentBlockhash)
        add(Fields.instructions)
        if (otherRequiredSigners.isNotEmpty()) add(Fields.otherSigner)
        if (unusedAccounts.isNotEmpty()) add(Fields.unusedAccount)
    }
    instructions.forEach { addAll(decodedFieldsOf(it)) }
}

/** The fields one instruction decodes: its program, and each field of its shape. */
private fun decodedFieldsOf(instruction: SolanaInstruction): Set<FieldPath> = buildSet {
    add(Fields.program)
    when (instruction) {
        is SolanaInstruction.SystemTransfer -> addAll(listOf(Fields.from, Fields.to, Fields.lamports))
        is SolanaInstruction.TokenTransfer -> addAll(listOf(Fields.source, Fields.destination, Fields.authority, Fields.amount))
        is SolanaInstruction.TokenTransferChecked ->
            addAll(listOf(Fields.source, Fields.mint, Fields.destination, Fields.authority, Fields.amount, Fields.decimals))
        is SolanaInstruction.AssociatedTokenCreate ->
            addAll(listOf(Fields.payer, Fields.account, Fields.owner, Fields.mint, Fields.idempotent))
        is SolanaInstruction.SetComputeUnitLimit -> add(Fields.units)
        is SolanaInstruction.SetComputeUnitPrice -> add(Fields.microLamports)
        is SolanaInstruction.SetHeapSize -> add(Fields.heapBytes)
        is SolanaInstruction.SetAccountDataLimit -> add(Fields.dataLimitBytes)
        is SolanaInstruction.AdvanceNonce -> addAll(listOf(Fields.nonceAccount, Fields.authority))
        is SolanaInstruction.CreateNonceAccount -> {
            addAll(listOf(Fields.funder, Fields.account, Fields.lamports, Fields.authority))
            if (instruction.seed != null) addAll(listOf(Fields.seedBase, Fields.seedText))
        }
        is SolanaInstruction.TokenApprove -> {
            addAll(listOf(Fields.source, Fields.delegate, Fields.owner, Fields.amount))
            if (instruction.mint != null) add(Fields.mint)
            if (instruction.decimals != null) add(Fields.decimals)
        }
        is SolanaInstruction.TokenSetAuthority ->
            addAll(listOf(Fields.account, Fields.authorityType, Fields.newAuthority, Fields.authority))
        is SolanaInstruction.TokenCloseAccount -> addAll(listOf(Fields.account, Fields.destination, Fields.owner))
        is SolanaInstruction.TokenBurn -> {
            addAll(listOf(Fields.account, Fields.mint, Fields.authority, Fields.amount))
            if (instruction.decimals != null) add(Fields.decimals)
        }
        is SolanaInstruction.SystemCreateAccount -> addAll(listOf(Fields.funder, Fields.account, Fields.lamports, Fields.space, Fields.owner))
        is SolanaInstruction.SystemAssign -> addAll(listOf(Fields.account, Fields.owner))
        is SolanaInstruction.WithdrawNonce -> addAll(listOf(Fields.nonceAccount, Fields.to, Fields.authority, Fields.lamports))
        is SolanaInstruction.AuthorizeNonce -> addAll(listOf(Fields.nonceAccount, Fields.newAuthority, Fields.authority))
        is SolanaInstruction.Raw -> {
            add(Fields.data)
            if (instruction.accounts.isNotEmpty()) add(Fields.accounts)
        }
    }
}

/** The decoded fields that the review does not show, with the reason it does not. */
private fun SolanaRequestReview.excludedFields(): Map<FieldPath, String> = buildMap {
    put(Fields.decimals, "the Token Transfer row shows the amount in the token's decimal places")
}
