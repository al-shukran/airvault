package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.airvault.chains.evm.signer.AccessListEntry
import app.airvault.core.model.ChainId
import app.lensly.app.AddressNotes
import app.lensly.app.EVM_FAMILY
import app.lensly.app.notesOf
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.registry.ProxyBinding
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.settings.protocolOfMeaning
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalMonoFamily


/** The warning that the request is not in Lensly's registry, and what the owner checks. */
private const val REGISTRY_TEXT =
    "Not in Lensly's registry. Lensly cannot confirm this network or token, so check the chain ID and contract against a trusted source."

/** The warning on a call the signed whitelist approves, which Lensly cannot decode itself. */
private const val WHITELIST_TEXT =
    "Lensly cannot decode this call itself. It is on your signed whitelist with the meaning above. Sign only if this is what you intend."

/** The warning on a whitelisted proxy under Warn and confirm: its code cannot be checked offline. */
private const val UPGRADE_TEXT =
    "The implementation could have been upgraded since it was approved, and Lensly cannot check it offline. The implementation it was " +
        "approved with is in the details."

/** The function name of an approval for every token of a collection, as the review names it. */
private const val SET_APPROVAL_FOR_ALL = "setApprovalForAll"

/** The label of the origin a request names. The request sets it, so the review does not vouch for it. */
private const val CLAIMED_ORIGIN_LABEL = "Claimed by request (not verified)"

/**
 * Transaction review. The header names the network in full, the account that signs with its wallet and address. A sentence says what
 * the request does, and under it come the network fee, the total and who requested it. Every warning sits in one banner, and each check
 * the review asks for sits in the row it belongs to. Every detail is shown on first render, in a scrollable column: the parameters, the
 * calls, the transaction, and the raw data. Each address names its Address Book entry, or says that it is not in the Address Book.
 *
 * The hold-to-sign control stays disabled until the user has read the review to its end, and until every check is ticked. A personal
 * message ([SIGN_MESSAGE_FUNCTION]) shows its text whole, under the banner, since the text is what is signed. A whitelisted call takes its
 * approved meaning as its sentence. REJECT is always enabled.
 */
@Composable
fun TxReviewScreen(
    review: TxReview,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens an address of the review in the Address Book, with its chain. Null leaves the address rows without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val isMessage = review.function == SIGN_MESSAGE_FUNCTION
    val open = addressTapOn(EVM_FAMILY, onOpenAddress)
    // The To row names the recipient, or the contract of a whitelisted or decoded call. A message has none.
    val showTo = !isMessage && review.toAddress.isNotEmpty()
    val read = rememberReviewRead(review)
    // The checks the review asks for, such as the chain ID and contract outside the registry. They reset with the review.
    var checked by remember(review) { mutableStateOf(emptySet<ReviewCheck>()) }
    val approvable = review.canApprove(read.read, checked)

    LenslyScreen(
        title = "Review",
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            ReviewActions(
                hint = reviewHint(review, read, checked),
                approvable = approvable,
                onApprove = onApprove,
                onReject = onReject,
            )
        },
    ) {
        ReviewBody(read) {
            ReviewHeader(
                network = review.networkName,
                testnet = review.network?.testnet == true,
                chainDetail = "Chain ID ${review.chainId}",
                account = review.fromAccountLabel,
                wallet = review.walletName,
                address = review.fromAddress,
                networkIcon = { ChainIcon(network = review.network, size = 24.dp) },
            )
            Spacer(Modifier.height(LenslySpacing.md))
            ReviewSummary(
                sentence = evmSentence(review, LocalMonoFamily.current),
                facts = evmFacts(review),
                icon = if (review.amount.isNotEmpty()) amountIcon(review) else null,
            )
            Spacer(Modifier.height(LenslySpacing.md))
            RiskBanner(
                items = evmRiskItems(review, checked) { check, on ->
                    checked = if (on) checked + check else checked - check
                },
            )
            Spacer(Modifier.height(LenslySpacing.md))
            if (isMessage) {
                MessageBlock(review)
                Spacer(Modifier.height(LenslySpacing.md))
            }
            // A call Lensly describes lists its steps in view, since the steps say what the call does.
            review.whitelisted?.steps?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                NumberedSteps(lines)
                Spacer(Modifier.height(LenslySpacing.sm))
            }
            if (!isMessage && review.typed == null) {
                ReviewSection(title = "Parameters") {
                    ParameterFields(review.params, review.addressNotes, open)
                }
            }
            review.typed?.let { typed ->
                ReviewSection(title = "Domain") {
                    typed.domain.forEach { (name, value) ->
                        ReviewField(label = name, value = value, notes = review.addressNotes.notesOf(value), onOpen = open)
                    }
                }
                ReviewSection(title = "Fields") {
                    typed.fields.forEach { row -> TypedFieldRow(row, review.addressNotes, open) }
                }
            }
            if (review.nested.isNotEmpty()) {
                ReviewSection(title = "Calls") {
                    review.nested.forEach { call -> NestedCallView(call, review.addressNotes, open) }
                }
            }
            ReviewSection(title = "Transaction") {
                TransactionFields(review, showTo, open)
            }
            review.whitelisted?.let { whitelisted ->
                ReviewSection(title = "Raw Data") {
                    RawDataFields(whitelisted)
                }
            }
            Spacer(Modifier.height(LenslySpacing.md))
        }
    }
}

/**
 * The line above the buttons. It names what still stands between the owner and signing, and it is empty when nothing does: the read of
 * the review first, then the checks the review asks for.
 */
private fun reviewHint(review: TxReview, read: ReviewRead, checked: Set<ReviewCheck>): String? {
    readHint(read)?.let { return it }
    val owed = review.requiredChecks.count { it !in checked }
    return when {
        owed == 1 -> "Tick the checkbox above to sign"
        owed > 1 -> "Tick the checkboxes above to sign"
        else -> null
    }
}

/** The text of the row of a check. Sentence case, in the body face. */
private val ReviewCheck.label: String
    get() = when (this) {
        ReviewCheck.DENYLIST -> "I understand this is a risky request"
        ReviewCheck.REGISTRY -> "I checked the chain ID and contract"
        ReviewCheck.UNKNOWN_CALL -> "I understand Lensly cannot say what this does."
        ReviewCheck.UNLIMITED -> "I understand this lets the spender take all of this token."
        ReviewCheck.WHITELIST -> "I checked this is what I intend"
        ReviewCheck.UPGRADE -> "I accept the implementation cannot be checked"
    }

/**
 * The rows of the risk banner: each warning the review holds, with the check that a row needs. A check goes to the first row that needs
 * it, so each check is shown, and ticked, once. The rows of the review come first, then those of its calls, in the order they run.
 */
internal fun evmRiskItems(review: TxReview, checked: Set<ReviewCheck>, onCheck: (ReviewCheck, Boolean) -> Unit): List<RiskItem> {
    val items = mutableListOf<RiskItem>()
    val attached = mutableSetOf<ReviewCheck>()
    fun row(text: String, tone: BannerTone, check: ReviewCheck? = null) {
        val shown = check?.takeIf { attached.add(it) }
        items += RiskItem(
            text = text,
            tone = tone,
            check = shown?.let { owed ->
                RiskCheck(label = owed.label, checked = owed in checked, onCheckedChange = { on -> onCheck(owed, on) })
            },
        )
    }
    review.denylisted.forEach { row("Known risky request. ${it.reason}", BannerTone.Error, ReviewCheck.DENYLIST) }
    if (review.needsRegistryConfirmation) row(REGISTRY_TEXT, BannerTone.Error, ReviewCheck.REGISTRY)
    review.warnings.forEach { row(it, BannerTone.Error, warningCheck(it)) }
    review.whitelisted?.let { whitelisted ->
        row(WHITELIST_TEXT, BannerTone.Warning, ReviewCheck.WHITELIST)
        if (whitelisted.call.proxy != null) row(UPGRADE_TEXT, BannerTone.Error, ReviewCheck.UPGRADE)
    }
    review.nested.forEach { call ->
        call.denylisted.forEach { row("${call.function}: known risky request. ${it.reason}", BannerTone.Error, ReviewCheck.DENYLIST) }
        if (call.unregistered) {
            row("${call.function}: outside Lensly's registry. Check its chain ID and contract.", BannerTone.Error, ReviewCheck.REGISTRY)
        }
        call.warnings.forEach { row("${call.function}: $it", BannerTone.Error, warningCheck(it)) }
        call.whitelisted?.let { whitelisted ->
            row("${call.function}: $WHITELIST_TEXT", BannerTone.Warning, ReviewCheck.WHITELIST)
            if (whitelisted.call.proxy != null) row("${call.function}: $UPGRADE_TEXT", BannerTone.Error, ReviewCheck.UPGRADE)
        }
    }
    return items
}

/**
 * The check a warning needs. An unknown call's warning needs the owner's tick that Lensly cannot say what the call does, and an
 * unlimited approval's warning needs the tick that the spender can take every token.
 */
private fun warningCheck(warning: String): ReviewCheck? = when {
    warning == UNKNOWN_CALL_WARNING -> ReviewCheck.UNKNOWN_CALL
    isUnlimitedApprovalWarning(warning) -> ReviewCheck.UNLIMITED
    else -> null
}

/**
 * The sentence of the summary: the verb, the amount and the counterparty. A personal message, a typed message, and a whitelisted call
 * each name what they are. A transfer names its amount and its recipient, an approval names its spender, and a call names its contract.
 */
internal fun evmSentence(review: TxReview, mono: FontFamily): AnnotatedString = buildAnnotatedString {
    val spender = review.params.firstOrNull { it.first.startsWith("spender") }?.second
    val amount = listOf(review.amount, review.symbol).filter { it.isNotEmpty() }.joinToString(" ")
    val whitelisted = review.whitelisted
    when {
        review.function == SIGN_MESSAGE_FUNCTION -> append("Sign a Message")
        review.typed != null -> append("Sign ${review.function}")
        whitelisted != null -> append(whitelisted.meaning)
        spender != null && review.amount.isNotEmpty() -> {
            append("Approve ")
            appendCounterparty(review, spender, mono)
            append(" to spend ")
            appendMono(amount, mono)
        }
        review.amount.isNotEmpty() -> {
            append("Send ")
            appendMono(amount, mono)
            if (review.toAddress.isNotEmpty()) {
                append(" to ")
                appendCounterparty(review, review.toAddress, mono)
            }
        }
        review.nested.isNotEmpty() -> {
            append("Run ${review.nested.count { it.depth == 1 }} calls")
            if (review.toAddress.isNotEmpty()) {
                append(" on ")
                appendCounterparty(review, review.toAddress, mono)
            }
        }
        review.function == SET_APPROVAL_FOR_ALL && review.tokenContract != null -> {
            // The operator is the counterparty, and the collection is the contract whose tokens it may move.
            if (review.params.firstOrNull { it.first == "approved" }?.second == "true") {
                append("Give ")
                appendCounterparty(review, review.toAddress, mono)
                append(" control of ")
                appendCounterparty(review, review.tokenContract, mono)
            } else {
                append("Remove control of ")
                appendCounterparty(review, review.tokenContract, mono)
                append(" from ")
                appendCounterparty(review, review.toAddress, mono)
            }
        }
        review.toAddress.isNotEmpty() -> {
            append("Call ${review.function} on ")
            appendCounterparty(review, review.toAddress, mono)
        }
        else -> append("Call ${review.function}")
    }
}

/** The name of an address from the Address Book or this device's accounts, or else its short form in mono. */
private fun AnnotatedString.Builder.appendCounterparty(review: TxReview, address: String, mono: FontFamily) {
    val name = review.addressNotes.notesOf(address)?.shownName()
    if (name != null) append(name) else appendMono(shortAddress(address), mono)
}

/**
 * The key/value list of the summary: the maximum network fee, the total when it can be added up, the typed message's own summary,
 * and the origin the request claims. An address of the typed summary shows its name when the Address Book or this device names it,
 * and its full address in the fields otherwise. The request names no origin as "Not stated".
 */
internal fun evmFacts(review: TxReview): List<ReviewFact> = buildList {
    val maxFee = review.fee.firstOrNull { it.first == "Max Fee" }?.second
    maxFee?.let { add(ReviewFact(label = "Network Fee", value = it)) }
    evmTotal(review, maxFee)?.let { add(ReviewFact(label = "Total", value = it)) }
    review.typed?.summary?.forEach { (label, value) ->
        val name = review.addressNotes.notesOf(value)?.shownName()
        add(ReviewFact(label = label, value = name ?: value, mono = name == null))
    }
    val origin = review.origin
    if (origin != null) {
        add(ReviewFact(label = CLAIMED_ORIGIN_LABEL, value = origin, mono = false))
    } else {
        add(ReviewFact(label = "Requested by", value = NOT_STATED, mono = false))
    }
}

/**
 * The total of a native transfer: the amount sent plus the maximum fee, when both are in the native coin. Null otherwise, because a
 * token amount cannot be added to a fee in the coin, and an unknown coin has no decimals to add with.
 */
internal fun evmTotal(review: TxReview, maxFee: String?): String? {
    if (review.tokenContract != null || maxFee == null || review.amount.isEmpty()) return null
    val fee = maxFee.split(' ')
    if (fee.size != 2 || fee[1] != review.symbol) return null
    val sent = review.amount.toBigDecimalOrNull() ?: return null
    val feeAmount = fee[0].toBigDecimalOrNull() ?: return null
    return "${(sent + feeAmount).toPlainString()} ${review.symbol}"
}

/** The logo of the amount: the coin's chain logo, or a token's logo with its chain as a badge. */
private fun amountIcon(review: TxReview): @Composable () -> Unit = { AmountIcon(review) }

@Composable
private fun AmountIcon(review: TxReview) {
    if (review.tokenContract == null) {
        ChainIcon(network = review.network, size = 32.dp)
    } else {
        TokenIcon(token = review.token, size = 32.dp, chain = review.network)
    }
}

/** A personal message: its text in the monospace face on a raised block, so each character can be checked by eye. Its size follows. */
@Composable
private fun MessageBlock(review: TxReview) {
    val text = review.params.firstOrNull { it.first == "message" }?.second.orEmpty()
    val size = review.params.firstOrNull { it.first == "size" }?.second
    Text(
        text = "Message",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(LenslySpacing.xxs))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(LenslySpacing.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    size?.let {
        Spacer(Modifier.height(LenslySpacing.xs))
        ReviewFactRow(label = "Size", value = it, mono = false)
    }
}

/** The steps of a decoded call, numbered in the order they run. Each step is one sentence, and it wraps when it must. */
@Composable
private fun NumberedSteps(lines: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, line ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = LenslySpacing.sm)) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(LenslySpacing.sm))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The arguments of a call, or "None" when it has none. Each address shows its names. */
@Composable
private fun ParameterFields(params: List<Pair<String, String>>, notes: Map<String, AddressNotes>, open: ((String) -> Unit)?) {
    if (params.isEmpty()) {
        NoteText("None")
    } else {
        params.forEach { (name, value) -> ReviewField(label = name, value = value, notes = notes.notesOf(value), onOpen = open) }
    }
}

/** One line of a typed message, indented by its depth. A header line names a struct or an array and its type. */
@Composable
private fun TypedFieldRow(row: TypedRow, notes: Map<String, AddressNotes>, open: ((String) -> Unit)?) {
    val label = "${row.label} · ${row.type}"
    Column(modifier = Modifier.fillMaxWidth().padding(start = (row.depth * 12).dp)) {
        val value = row.value
        if (value != null) {
            ReviewField(label = label, value = value, notes = notes.notesOf(value), onOpen = open)
        } else {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One call of a multicall, indented by its level so the nesting reads at a glance. Its warnings are in the banner above, so the call
 * shows its target, its function and its arguments, and the approved meaning of a call the whitelist approves.
 */
@Composable
private fun NestedCallView(call: NestedCall, notes: Map<String, AddressNotes>, open: ((String) -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LenslySpacing.md * (call.depth - 1), bottom = LenslySpacing.sm),
    ) {
        Text(
            text = call.function,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        call.target?.let { target -> ReviewField(label = "Target", value = target, notes = notes.notesOf(target), onOpen = open) }
        call.params.forEach { (name, value) -> ReviewField(label = name, value = value, notes = notes.notesOf(value), onOpen = open) }
        call.whitelisted?.let { whitelisted ->
            Text(
                text = whitelisted.meaning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            whitelisted.steps?.lines?.takeIf { it.isNotEmpty() }?.let { NumberedSteps(it) }
            ReviewField(label = "Function Signature", value = whitelisted.signature)
            ReviewFactRow(label = "Value", value = whitelisted.call.value)
            ReviewFactRow(label = "Selector", value = "0x${whitelisted.call.selector}")
            whitelisted.call.words.forEachIndexed { index, word ->
                ReviewField(label = "Word ${index + 1}", value = "0x${groupHex(word)}")
            }
            whitelisted.call.proxy?.let { proxy -> ProxyFields(proxy, notes, open) }
        }
        call.note?.let { note -> NoteText(note) }
    }
}

/**
 * The transaction: who signs and to whom, what it calls, its chain, and what it costs. The To row names its address from the Address
 * Book. A whitelisted call adds the protocol its approved meaning belongs to, and the implementation of a proxy it is bound to.
 */
@Composable
private fun TransactionFields(review: TxReview, showTo: Boolean, open: ((String) -> Unit)?) {
    val notes = review.addressNotes
    ReviewField(label = "From", value = review.fromAddress, notes = notes.notesOf(review.fromAddress), onOpen = open)
    if (showTo) {
        ReviewField(label = "To", value = review.toAddress, notes = notes.notesOf(review.toAddress), onOpen = open)
    }
    review.whitelisted?.let { whitelisted ->
        protocolOfMeaning(whitelisted.meaning).takeIf { it.isNotEmpty() }?.let { protocol ->
            ReviewFactRow(label = "Known Contract", value = protocol, mono = false)
        }
        ReviewField(label = "Function Signature", value = whitelisted.signature)
        // The To row names the contract, so the block leaves it out when that row shows.
        if (!showTo) {
            ReviewField(label = "Contract", value = whitelisted.call.target, notes = notes.notesOf(whitelisted.call.target), onOpen = open)
        }
        whitelisted.call.proxy?.let { proxy -> ProxyFields(proxy, notes, open) }
    }
    review.tokenContract?.let { contract ->
        if (review.token == null) {
            ReviewFactRow(label = "Token", value = UNKNOWN_TOKEN_NAME, mono = false)
        }
        ReviewField(label = "Token Contract", value = contract, notes = notes.notesOf(contract), onOpen = open)
    }
    if (review.function != SIGN_MESSAGE_FUNCTION) {
        ReviewFactRow(label = "Function", value = review.function, mono = false)
    }
    review.signature?.let { ReviewField(label = "Signature", value = it) }
    ReviewFactRow(label = "Chain ID", value = review.chainId.toString())
    if (review.typed == null && review.function != SIGN_MESSAGE_FUNCTION) {
        review.fee.forEach { (name, value) -> ReviewFactRow(label = name, value = value) }
        review.feeNote?.let { note -> NoteText(note) }
        ReviewFactRow(label = "Nonce", value = review.nonce.toString())
        if (review.accessList.isNotEmpty()) AccessListFields(review.accessList, notes, open)
    }
    ReviewFactRow(label = "Type", value = review.txType, mono = false)
}

/**
 * The access list of a transaction: the addresses and storage keys it warms, counted, then each address with its keys. Each address names
 * its Address Book entry. The access list is signed, so the owner can read what it warms.
 */
@Composable
private fun AccessListFields(entries: List<AccessListEntry>, notes: Map<String, AddressNotes>, open: ((String) -> Unit)?) {
    val keys = entries.sumOf { it.storageKeys.size }
    val addresses = "${entries.size} ${if (entries.size == 1) "address" else "addresses"}"
    val storageKeys = "$keys ${if (keys == 1) "storage key" else "storage keys"}"
    ReviewFactRow(label = "Access List", value = "$addresses, $storageKeys", mono = false)
    entries.forEachIndexed { index, entry ->
        ReviewField(label = "Address ${index + 1}", value = entry.address, notes = notes.notesOf(entry.address), onOpen = open)
        entry.storageKeys.forEachIndexed { keyIndex, key ->
            ReviewField(label = "Storage key ${keyIndex + 1}", value = "0x${groupHex(key.removePrefix("0x"))}")
        }
    }
}

/** The implementation a whitelisted proxy is bound to, in full: its address, its code hash, or both. */
@Composable
private fun ProxyFields(proxy: ProxyBinding, notes: Map<String, AddressNotes>, open: ((String) -> Unit)?) {
    proxy.implementationAddress?.let { address ->
        ReviewField(label = "Approved Implementation", value = address, notes = notes.notesOf(address), onOpen = open)
    }
    proxy.implementationCodeHash?.let { hash ->
        ReviewField(label = "Approved Code Hash", value = groupHex(hash.removePrefix("0x")))
    }
}

/** The value and the calldata of a whitelisted call: its value, its selector, and every word after the selector in full. */
@Composable
private fun RawDataFields(whitelisted: WhitelistedCall) {
    ReviewFactRow(label = "Value", value = whitelisted.call.value)
    ReviewFactRow(label = "Selector", value = "0x${whitelisted.call.selector}")
    val words = whitelisted.call.words
    if (words.isEmpty()) {
        NoteText("None")
    } else {
        words.forEachIndexed { index, word ->
            ReviewField(label = "Word ${index + 1}", value = "0x${groupHex(word)}")
        }
    }
}
