package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import app.airvault.core.model.ChainId
import app.lensly.app.AddressNotes
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.SigningAccount
import app.lensly.app.notesOf
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcReview
import app.lensly.core.btc.BtcReviewInput
import app.lensly.core.btc.BtcWarning
import app.lensly.core.btc.LOCK_TIME_THRESHOLD
import app.lensly.core.btc.ScriptType
import app.lensly.core.btc.warnings
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalMonoFamily
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The check that unlocks signing when a review has unverified amounts. */
private const val UNVERIFIED_CHECK = "I have checked the unverified input amounts"

/**
 * The Bitcoin review, the counterpart of [TxReviewScreen]. The header names the network in full, the account that signs with its wallet
 * and address. A sentence says what the request sends and to whom, and a short list under it gives the fee, the fee rate, the total and
 * the change. Every warning sits in one banner, and the check on unverified amounts sits inside that banner. Hold to sign is off until the
 * review is read to its end and, when the amounts are unverified, until the owner ticks the check. The inputs, the outputs and the
 * transaction are all shown on first render. REJECT is always enabled.
 *
 * [confirmed] is the owner's check of the unverified amounts, and [onConfirmedChange] reports a change of it. [signer] is the account
 * that signs.
 */
@Composable
fun BitcoinReviewScreen(
    review: BtcReview,
    network: BtcNetwork,
    scriptType: ScriptType,
    signer: SigningAccount,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the Address Book and the wallet's own accounts say of each output address, keyed by app.lensly.app.addressKeyOf. */
    addressNotes: Map<String, AddressNotes> = emptyMap(),
    /** Opens an output address in the Address Book, with its chain. Null leaves the address rows without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val open = addressTapOn(BITCOIN_FAMILY, onOpenAddress)
    // A different request starts unread and with its check unticked, so nothing carries over from the last one.
    key(review) {
        val read = rememberReviewRead(review)
        LenslyScreen(
            title = "Review",
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                ReviewActions(
                    hint = btcHint(review, read, confirmed),
                    approvable = read.read && (!review.requiresConfirmation || confirmed),
                    onApprove = onApprove,
                    onReject = onReject,
                )
            },
        ) {
            ReviewBody(read) {
                ReviewHeader(
                    network = bitcoinNetworkName(network),
                    testnet = network == BtcNetwork.TESTNET4,
                    chainDetail = null,
                    account = signer.name,
                    wallet = signer.walletName,
                    address = signer.address,
                    bitcoin = true,
                )
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSummary(sentence = btcSentence(review, addressNotes, LocalMonoFamily.current), facts = btcFacts(review))
                Spacer(Modifier.height(LenslySpacing.md))
                RiskBanner(items = btcRiskItems(review, confirmed, onConfirmedChange))
                Spacer(Modifier.height(LenslySpacing.md))
                ReviewSection(title = "Inputs") {
                    review.inputs.forEach { input ->
                        ReviewFactRow(label = "Input ${input.index}", value = btcText(input.amountSats))
                        if (input.ownerPath != null) {
                            NoteText("Owned by this wallet, so Lensly signs it")
                        } else {
                            NoteText("Not owned by this account, so Lensly does not sign it")
                        }
                        if (!input.amountVerified) NoteText("Amount not verified against its previous transaction")
                        NoteText(sequenceText(input))
                        Spacer(Modifier.height(LenslySpacing.sm))
                    }
                }
                ReviewSection(title = "Outputs") {
                    review.outputs.forEach { output ->
                        ReviewFactRow(label = "Output ${output.index}", value = btcText(output.amountSats))
                        val address = output.address
                        if (address != null) {
                            ReviewAddress(
                                address = address,
                                notes = addressNotes.notesOf(address),
                                bitcoin = true,
                                onClick = open?.let { tap -> { tap(address) } },
                            )
                        } else {
                            NoteText("No address. The script is not paid to an address.")
                        }
                        if (output.isOwnChange) NoteText("Your change, at change index ${output.changeIndex}")
                        Spacer(Modifier.height(LenslySpacing.sm))
                    }
                }
                ReviewSection(title = "Transaction") {
                    ReviewFactRow(label = "Script Type", value = scriptTypeName(scriptType), mono = false)
                    ReviewFactRow(
                        label = "Fee",
                        value = review.feeSats?.let { "$it sat" } ?: "Unknown",
                        mono = review.feeSats != null,
                    )
                    ReviewFactRow(label = "Estimated Size", value = "${review.estimatedVsize} vB")
                    NoteText("Estimated from the unsigned request, so the signed size may differ by a few vbytes.")
                    ReviewFactRow(label = "Locktime", value = lockTimeText(review.lockTime), mono = review.lockTime != 0L)
                    if (review.lockTime != 0L && !review.lockTimeEnforced) {
                        NoteText("Not enforced, because every input is final.")
                    }
                    if (review.feeSats == null) {
                        NoteText("Unknown until every input amount is verified or confirmed.")
                    }
                    Spacer(Modifier.height(LenslySpacing.sm))
                }
            }
        }
    }
}

/**
 * The line above the sign control: the read of the review first, then the check on unverified amounts, which sits in the banner.
 */
private fun btcHint(review: BtcReview, read: ReviewRead, confirmed: Boolean): String? {
    readHint(read)?.let { return it }
    return if (review.requiresConfirmation && !confirmed) "Tick the checkbox above to sign" else null
}

/** The Script Type a review shows, with its address kind. */
internal fun scriptTypeName(scriptType: ScriptType): String = when (scriptType) {
    ScriptType.LEGACY -> "Legacy P2PKH"
    ScriptType.NESTED_SEGWIT -> "Nested Segwit P2SH-P2WPKH"
    ScriptType.NATIVE_SEGWIT -> "Native Segwit P2WPKH"
    ScriptType.TAPROOT -> "Taproot P2TR"
}

/** An amount in satoshis as BTC, with all eight decimal places. */
internal fun btcText(sats: Long): String {
    val sign = if (sats < 0) "-" else ""
    val magnitude = kotlin.math.abs(sats)
    return "$sign${magnitude / SATS_PER_BTC}.${(magnitude % SATS_PER_BTC).toString().padStart(8, '0')} BTC"
}

/** An amount in BTC with no zeros at its end, for the summary sentence. Only the trailing zeros go, so the value stays exact. */
internal fun btcShort(sats: Long): String = btcText(sats).removeSuffix(" BTC").trimEnd('0').trimEnd('.') + " BTC"

/** An address shortened to its first and last characters, for a sentence with no room for the full one. */
internal fun shortAddress(address: String): String =
    if (address.length <= SHORT_ADDRESS_LIMIT) address else "${address.take(10)}...${address.takeLast(6)}"

/** Sats the request sends to others: every output that is not the wallet's change. */
internal fun BtcReview.sentToOthersSats(): Long = outputs.filterNot { it.isOwnChange }.sumOf { it.amountSats }

/** Sats the request returns to this Account as change. */
internal fun BtcReview.changeBackSats(): Long = outputs.filter { it.isOwnChange }.sumOf { it.amountSats }

/** What leaves the wallet: the sats sent to others, plus the fee. Null while the fee is unknown. */
internal fun BtcReview.totalSats(): Long? = feeSats?.let { sentToOthersSats() + it }

/**
 * The summary sentence: the verb, the amount sent to others, and the counterparty. A single outside output names its counterparty by
 * its Address Book name, or else by its full address, and several are counted. A request that pays nothing outside the wallet says so.
 */
internal fun btcSentence(review: BtcReview, notes: Map<String, AddressNotes>, mono: FontFamily): AnnotatedString {
    val others = review.outputs.filterNot { it.isOwnChange }
    return buildAnnotatedString {
        when (others.size) {
            0 -> append("Pay nothing outside this wallet")
            1 -> {
                val output = others.single()
                val address = output.address
                append("Send ")
                appendMono(btcShort(output.amountSats), mono)
                append(" to ")
                val name = address?.let { notes.notesOf(it)?.shownName() }
                when {
                    name != null -> append(name)
                    address != null -> appendMono(address, mono)
                    else -> append("a script with no address")
                }
            }
            else -> {
                append("Send ")
                appendMono(btcShort(review.sentToOthersSats()), mono)
                append(" to ${others.size} addresses")
            }
        }
    }
}

/**
 * The key/value list under the sentence. The fee, the fee rate and the total are unknown while an input amount is unverified, and they
 * say so in words. The change back is "None" when the request returns no change.
 */
internal fun btcFacts(review: BtcReview): List<ReviewFact> {
    val fee = review.feeSats
    val rate = review.feeRateSatPerVb
    val total = review.totalSats()
    val change = review.changeBackSats()
    return listOf(
        ReviewFact(label = "Network Fee", value = fee?.let(::btcText) ?: "Unknown", mono = fee != null),
        ReviewFact(
            label = "Fee Rate",
            value = rate?.let { String.format(Locale.ROOT, "%.1f sat/vB", it) } ?: "Unknown",
            mono = rate != null,
        ),
        ReviewFact(label = "Total", value = total?.let(::btcText) ?: "Unknown", mono = total != null),
        ReviewFact(label = "Change Back", value = if (change > 0) btcText(change) else "None", mono = change > 0),
    )
}

/**
 * The rows of the risk banner: each warning of the review, in the order the review holds them. The owner's check on the unverified
 * amounts sits inside that row, when the review asks for one.
 */
private fun btcRiskItems(review: BtcReview, confirmed: Boolean, onConfirmedChange: (Boolean) -> Unit): List<RiskItem> =
    review.warnings().map { warning ->
        val check = if (warning is BtcWarning.UnverifiedAmounts && review.requiresConfirmation) {
            RiskCheck(label = UNVERIFIED_CHECK, checked = confirmed, onCheckedChange = onConfirmedChange)
        } else {
            null
        }
        RiskItem(text = warningText(warning), tone = warningTone(warning), check = check)
    }

/**
 * How severe a warning is. Unverified amounts are red, because a fee cannot be checked while they stand. The rest are amber.
 */
internal fun warningTone(warning: BtcWarning): BannerTone = when (warning) {
    is BtcWarning.UnverifiedAmounts -> BannerTone.Error
    is BtcWarning.ExternalInputs, is BtcWarning.UnknownChange, is BtcWarning.LockTime,
    is BtcWarning.HighFeeRate, is BtcWarning.HighFeeShare -> BannerTone.Warning
}

/** The words a warning shows on the review. */
internal fun warningText(warning: BtcWarning): String = when (warning) {
    is BtcWarning.ExternalInputs -> "${warning.count} input(s) belong to another signer. Lensly does not sign them."
    is BtcWarning.UnverifiedAmounts ->
        "${warning.count} input amount(s) come from a witness UTXO only. They are not verified, so the fee is unknown until " +
            "they are confirmed."
    is BtcWarning.UnknownChange ->
        "${warning.count} output(s) name a key of this wallet but do not pay this account's change. Check where they go."
    is BtcWarning.HighFeeRate ->
        "The fee rate is ${String.format(Locale.ROOT, "%.1f", warning.satPerVb)} sat/vB, above your limit of " +
            "${warning.thresholdSatPerVb} sat/vB."
    is BtcWarning.HighFeeShare ->
        "The fee is ${String.format(Locale.ROOT, "%.1f", warning.percent)}% of the amount sent to others, above your limit " +
            "of ${warning.thresholdPercent}%."
    is BtcWarning.LockTime -> "The transaction cannot confirm before ${lockTimePoint(warning.lockTime)}."
}

/** The point a locktime holds the transaction until: "block N" for a block height, else the UTC date and time of a Unix time. */
internal fun lockTimePoint(lockTime: Long): String =
    if (lockTime < LOCK_TIME_THRESHOLD) "block $lockTime" else LOCK_TIME_DATE.format(Instant.ofEpochSecond(lockTime))

/** A locktime as the Transaction section shows it: "None" for zero, otherwise its point with a capital first letter. */
internal fun lockTimeText(lockTime: Long): String =
    if (lockTime == 0L) "None" else lockTimePoint(lockTime).replaceFirstChar { it.uppercaseChar() }

/** The sequence of an input and its replace-by-fee state, for the input's row. */
internal fun sequenceText(input: BtcReviewInput): String {
    val sequence = String.format(Locale.ROOT, "0x%08X", input.sequence)
    return "Sequence $sequence, replace-by-fee ${if (input.replaceableByFee) "on" else "off"}"
}

private val LOCK_TIME_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC)

private const val SATS_PER_BTC = 100_000_000L

/** Addresses longer than this are shortened in the summary sentence. */
private const val SHORT_ADDRESS_LIMIT = 16
