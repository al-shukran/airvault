package app.lensly.ui.sign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcReview
import app.lensly.core.btc.BtcWarning
import app.lensly.core.btc.ScriptType
import app.lensly.core.btc.warnings
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslySpacing
import java.util.Locale

/**
 * The Bitcoin review, the counterpart of [TxReviewScreen]: every input and output of the request, the fee and the fee rate, the
 * network and the Script Type, and the warnings the review holds. A warning is shown on the review. HOLD TO SIGN is off while the review
 * asks for a confirmation of its unverified amounts and the owner has not given it. REJECT is always enabled.
 *
 * [confirmed] is the owner's check of the unverified amounts, and [onConfirmedChange] reports a change of it.
 */
@Composable
fun BitcoinReviewScreen(
    review: BtcReview,
    network: BtcNetwork,
    scriptType: ScriptType,
    accountIndex: Int,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Bitcoin",
        modifier = modifier,
        bottomAction = {
            if (review.requiresConfirmation) {
                CheckRow(
                    label = "I have checked the unverified input amounts",
                    checked = confirmed,
                    onCheckedChange = onConfirmedChange,
                )
                Spacer(Modifier.height(LenslySpacing.sm))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                RejectPill(onClick = onReject, modifier = Modifier.weight(1f))
                HoldToSignButton(
                    enabled = !review.requiresConfirmation || confirmed,
                    onSign = onApprove,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            PillChip(text = networkName(network))
            PillChip(text = scriptTypeName(scriptType))
            PillChip(text = "Account $accountIndex")
        }
        Spacer(Modifier.height(LenslySpacing.md))
        review.warnings().forEach { warning ->
            InlineBanner(message = warningText(warning), tone = BannerTone.Error, title = "Warning")
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        Text(
            text = "Inputs",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        review.inputs.forEach { input ->
            FactRow(label = "Input ${input.index}") {
                MonoText(btcText(input.amountSats))
                if (input.ownerPath != null) {
                    NoteText("Owned by this wallet, so Lensly signs it")
                } else {
                    MutedText("Not owned by this account, so Lensly does not sign it")
                }
                if (!input.amountVerified) MutedText("Amount not verified against its previous transaction")
            }
        }
        Text(
            text = "Outputs",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        review.outputs.forEach { output ->
            FactRow(label = "Output ${output.index}") {
                output.address?.let { AddressText(it) } ?: MutedText("No address. The script is not paid to an address.")
                MonoText(btcText(output.amountSats))
                if (output.isOwnChange) NoteText("Your change, at change index ${output.changeIndex}")
            }
        }
        FactRow(label = "Fee") {
            MonoText(review.feeSats?.let { "$it sat (${btcText(it)})" } ?: "Unknown")
            if (review.feeSats == null) MutedText("Unknown until every input amount is verified or confirmed.")
        }
        FactRow(label = "Fee rate") {
            MonoText(review.feeRateSatPerVb?.let { String.format(Locale.ROOT, "%.1f sat/vB", it) } ?: "Unknown")
            MutedText("Estimated from the unsigned request, so the signed size may differ by a few vbytes.")
        }
    }
}

/** The network name a review shows. Testnet4 is the test network, and it is named as such on screen. */
internal fun networkName(network: BtcNetwork): String = when (network) {
    BtcNetwork.MAINNET -> "Mainnet"
    BtcNetwork.TESTNET4 -> "Testnet4"
}

/** The Script Type a review shows, with its address kind. */
internal fun scriptTypeName(scriptType: ScriptType): String = when (scriptType) {
    ScriptType.LEGACY -> "Legacy P2PKH"
    ScriptType.NESTED_SEGWIT -> "Nested segwit P2SH-P2WPKH"
    ScriptType.NATIVE_SEGWIT -> "Native segwit P2WPKH"
    ScriptType.TAPROOT -> "Taproot P2TR"
}

/** An amount in satoshis as BTC, with all eight decimal places. */
internal fun btcText(sats: Long): String {
    val sign = if (sats < 0) "-" else ""
    val magnitude = kotlin.math.abs(sats)
    return "$sign${magnitude / SATS_PER_BTC}.${(magnitude % SATS_PER_BTC).toString().padStart(8, '0')} BTC"
}

/** The words a warning shows on the review. */
internal fun warningText(warning: BtcWarning): String = when (warning) {
    is BtcWarning.ExternalInputs -> "${warning.count} input(s) belong to another signer. Lensly does not sign them."
    is BtcWarning.UnverifiedAmounts ->
        "${warning.count} input amount(s) come from a witness UTXO only. They are not verified, so the fee is unknown until they are confirmed."
    is BtcWarning.UnknownChange ->
        "${warning.count} output(s) name a key of this wallet but do not pay this account's change. Check where they go."
}

private const val SATS_PER_BTC = 100_000_000L
