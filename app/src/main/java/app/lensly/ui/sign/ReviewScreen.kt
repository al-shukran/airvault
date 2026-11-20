package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.SectionStyle
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.airvault.core.model.checkLabels
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.app.notesOf
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalLumenPalette
import app.lensly.ui.theme.LocalMonoFamily
import app.lensly.ui.theme.LumenPalette

/**
 * The one review screen for every chain (spec 3.2). The core builds a [Review] and this screen draws it, so no chain draws its own. The
 * header names the network and the signing account. The summary sentence sits over its facts, and its counterparties are named from the
 * Address Book or this device. Every warning goes in one banner: an error for a danger or a block, information for an info, a warning
 * otherwise. A check that a warning names is ticked once, in the first row that names it, and the review cannot be signed until every
 * check is ticked. Each section lists its rows, all shown on first render, indented by their depth. A steps section numbers its rows, and a
 * message shows its text on a raised block. The request is signed only after it is read to its end, and only when no warning is a block.
 * REJECT is always enabled.
 */
@Composable
fun ReviewScreen(
    review: Review,
    signer: SigningAccount,
    addressNotes: Map<String, AddressNotes>,
    addressChain: ChainId,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens an address of the review in the Address Book, with its chain. Null leaves the address rows without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
    /** The logo of the network, shown before its name in the header. Null shows the name alone. */
    networkIcon: (@Composable () -> Unit)? = null,
    /** The logo of the amount, shown before the summary sentence. Null shows the sentence alone. */
    summaryIcon: (@Composable () -> Unit)? = null,
    /** The registry's networks, which give each address row the logo of the request's network. */
    networks: List<NetworkEntry> = emptyList(),
    /** The checks ticked on this review, by label. The sign flow holds them and enforces them on approve, so this screen only shows them. */
    ticked: Set<String> = emptySet(),
    /** The owner ticked, or unticked, a check: its label and its new tick. */
    onTick: (String, Boolean) -> Unit = { _, _ -> },
) {
    val open = addressTapOn(addressChain, onOpenAddress)
    // A different request starts unread, so nothing carries over from the last one.
    key(review) {
        val read = rememberReviewRead(review)
        val owed = review.checkLabels().count { it !in ticked }
        val addressLogo: @Composable () -> Unit = {
            ChainNetworkLogo(chain = review.chain, networkName = review.network.name, networks = networks, size = 16.dp)
        }
        LenslyScreen(
            title = review.title,
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                ReviewActions(
                    hint = readHint(read) ?: tickHint(owed),
                    approvable = read.read && owed == 0 && review.warnings.none { it.level == Danger.BLOCK } &&
                        review.unaccounted().isEmpty(),
                    onApprove = onApprove,
                    onReject = onReject,
                )
            },
        ) {
            ReviewBody(read) {
                ReviewHeader(
                    network = review.network.name,
                    testnet = review.network.testnet,
                    chainDetail = review.network.detail,
                    networkFact = review.network.row?.let { fact(it) },
                    account = signer.name,
                    accountLabel = if (signer.isAddressKey) "Key" else "Account",
                    wallet = signer.walletName,
                    address = signer.address,
                    addressLogo = addressLogo,
                    networkIcon = networkIcon,
                )
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSummary(
                    sentence = summarySentence(review.summary, addressNotes, signer, LocalMonoFamily.current, LocalLumenPalette.current),
                    facts = review.facts.map { fact(it) },
                    icon = summaryIcon,
                )
                Spacer(Modifier.height(LenslySpacing.md))
                RiskBanner(items = riskItems(review.warnings, ticked, onTick))
                Spacer(Modifier.height(LenslySpacing.md))
                review.sections.forEach { section ->
                    ReviewSection(title = section.heading) {
                        section.rows.forEachIndexed { index, row ->
                            val step = if (section.style == SectionStyle.Steps) index + 1 else null
                            ReviewRowView(row = row, step = step, notes = addressNotes, open = open, logo = addressLogo)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row of a section. A step is numbered, and its label is its sentence. A message shows its text on a raised block. A row with no value
 * is a heading. Any other row is a label over its value, and an address in it shows its names. Each row is indented by its depth.
 */
@Composable
private fun ReviewRowView(
    row: ReviewRow,
    step: Int?,
    notes: Map<String, AddressNotes>,
    open: ((String) -> Unit)?,
    logo: @Composable () -> Unit,
) {
    val indent = Modifier.padding(start = LenslySpacing.md * row.depth)
    val value = row.value
    when {
        step != null -> ReviewStep(number = step, text = row.label, modifier = indent)
        value == null -> ReviewHeading(label = row.label, modifier = indent)
        value is DisplayValue.Message -> MessageBlock(label = row.label, text = value.text, modifier = indent)
        else -> {
            val text = value.text()
            ReviewField(
                label = row.label,
                value = text,
                logo = logo,
                notes = notes.notesOf(text),
                modifier = indent,
                onOpen = open,
                isAddress = value is DisplayValue.Address,
                lines = row.notes,
            )
        }
    }
}

/** The line above the buttons while checks are still to tick: singular for one, plural for more. Null when none is owed. */
internal fun tickHint(owed: Int): String? = when {
    owed == 1 -> "Tick the checkbox above to sign"
    owed > 1 -> "Tick the checkboxes above to sign"
    else -> null
}

/**
 * The rows of the risk banner, one per warning, with the tone of its level. The first row that names a check carries its tick, and a later
 * row with the same check shows its text only, so the check is shown and ticked once. [onTick] gets the check's label and its new tick.
 */
internal fun riskItems(warnings: List<Warning>, ticked: Set<String>, onTick: (String, Boolean) -> Unit): List<RiskItem> {
    val shown = mutableSetOf<String>()
    return warnings.map { warning ->
        // takeIf adds the label to shown only when it is new, so only the first row that names it gets the tick.
        val label = warning.acknowledge?.takeIf { shown.add(it) }
        RiskItem(
            text = warning.message,
            tone = when (warning.level) {
                Danger.DANGER, Danger.BLOCK -> BannerTone.Error
                Danger.INFO -> BannerTone.Info
                Danger.CAUTION -> BannerTone.Warning
            },
            check = label?.let { name ->
                RiskCheck(label = name, checked = name in ticked, onCheckedChange = { on -> onTick(name, on) })
            },
        )
    }
}

/**
 * The summary sentence. A send is the question it opens with, "Send <amount> <asset> from <sender> to <recipient>?". Each party is named
 * from this device or the Address Book, or else shown in full. Any other sentence keeps its text, and each counterparty in it is named
 * from this device or the Address Book, or else shown short. Amounts are in the monospace face. Every name is coloured by its role.
 */
internal fun summarySentence(
    parts: List<SummaryPart>,
    notes: Map<String, AddressNotes>,
    signer: SigningAccount,
    mono: FontFamily,
    palette: LumenPalette,
): AnnotatedString = buildAnnotatedString {
    parts.forEach { part ->
        when (part) {
            is SummaryPart.Text -> append(part.text)
            is SummaryPart.Mono -> appendMono(part.text, mono)
            is SummaryPart.Counterparty -> {
                val known = notes.notesOf(part.address)
                val name = known?.shownName()
                val role = nameRoleOf(known)
                if (name != null && role != null) appendNamed(name, role, palette) else appendMono(shortAddress(part.address), mono)
            }
            is SummaryPart.Send -> {
                append("Send ")
                appendMono("${part.amount} ${part.asset}", mono)
                append(" from ")
                appendParty(part.from, sendPartyOf(part.from, notes, signer), mono, palette)
                append(" to ")
                appendParty(part.to, sendPartyOf(part.to, notes, signer), mono, palette)
                append("?")
            }
        }
    }
}

/** A party of a send: its name in its role's colour, or its address in full in mono when no name is known. */
private fun AnnotatedString.Builder.appendParty(address: String, name: PartyName?, mono: FontFamily, palette: LumenPalette) {
    if (name == null) appendMono(address, mono) else appendNamed(name.text, name.role, palette)
}

/** Appends [name] in the colour of [role]. */
private fun AnnotatedString.Builder.appendNamed(name: String, role: NameRole, palette: LumenPalette) {
    withStyle(SpanStyle(color = role.colourIn(palette))) { append(name) }
}

/** A row as a summary fact: set in the monospace face when its value is an amount, an address or data, or when the value asks for it. */
private fun fact(row: ReviewRow): ReviewFact {
    val value = checkNotNull(row.value) { "a summary fact shows its value" }
    return ReviewFact(label = row.label, value = value.text(), mono = value !is DisplayValue.Text || value.mono)
}
