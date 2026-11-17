package app.lumio.ui.btc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.app.AddressBookEntry
import app.lensly.app.addressNotesOf
import app.lensly.core.btc.BtcNetwork
import app.lensly.ui.components.AddressField
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.components.PillChip
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.theme.LenslySpacing
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcAdvanced
import app.lumio.btc.BtcSendInput
import app.lumio.btc.BtcSendPlan
import app.lumio.btc.btcText
import app.lumio.ui.pair.PairLabels
import app.lumio.ui.send.ReviewAddress
import app.lumio.ui.send.ReviewField
import app.lumio.ui.send.SectionLabel

private const val TESTNET_LABEL = "TESTNET"

/**
 * The Bitcoin send form: the account that pays, which the send opened from and so is fixed, the recipient, the amount and the fee rate.
 * [feeSuggestion] says where the fee rate starts, and [notice] is why the last attempt did not go through. [busy] holds the review until the server
 * has answered.
 */
@Composable
fun BtcSendFormScreen(
    account: BtcAccountRef,
    recipient: String,
    onRecipient: (String) -> Unit,
    onScan: () -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    feeRate: String,
    onFeeRate: (String) -> Unit,
    feeSuggestion: String?,
    busy: Boolean,
    notice: String?,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "SEND BITCOIN",
        onBack = onBack,
        bottomAction = {
            WalletPill(
                text = "Review",
                onClick = onReview,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
                enabled = !busy,
            )
        },
    ) {
        if (busy) {
            Text(
                "Reading the network",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("From")
        AccountLine(ref = account)

        SectionLabel("Send to")
        AddressField(
            value = recipient,
            onValueChange = onRecipient,
            label = "Recipient address",
            validate = { text -> btcRecipientError(text, account.account.network) },
            enabled = !busy,
            onScan = onScan,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        OutlinedTextField(
            value = amount,
            onValueChange = onAmount,
            label = { Text("Amount in BTC") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        OutlinedTextField(
            value = feeRate,
            onValueChange = onFeeRate,
            label = { Text("Fee rate in sat/vB") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        feeSuggestion?.let {
            Spacer(Modifier.height(LenslySpacing.xs))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/**
 * The review of a Bitcoin send before anything is requested: what leaves, where it goes, the fee, and where the change goes. [suggestedRate]
 * is the fee rate the server suggests. Advanced edits the rate, and [onSave] takes the rate the owner keeps.
 */
@Composable
fun BtcSendReviewScreen(
    plan: BtcSendPlan,
    suggestedRate: Double,
    notice: String?,
    onSave: (Double) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    addressBook: List<AddressBookEntry> = emptyList(),
) {
    var advanced by remember { mutableStateOf(false) }
    LenslyScreen(
        title = "REVIEW SEND",
        onBack = onBack,
        bottomAction = {
            WalletPill(
                text = "Sign with Lensly",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        NetworkHeader(plan.network)
        ReviewField("From", "${PairLabels.scriptType(plan.account.scriptType)}, ${plan.account.path.asString('\'')}")
        ReviewAddress("To", plan.recipient, name = addressNotesOf(plan.recipient, emptyMap(), addressBook)?.entry?.name)
        ReviewField("Amount", btcText(plan.amountSats))
        ReviewField("Fee", "${btcText(plan.feeSats)}, at ${rateText(plan.feeRateSatPerVb)} sat/vB for about ${plan.vsize} vB")
        ReviewField(
            "Change",
            plan.changeIndex?.let { "${btcText(plan.changeSats)}, to change address $it" } ?: "None. The remainder goes to the fee.",
        )
        ReviewField("Spends", "${plan.coins.size} output(s), ${btcText(plan.inputSats)}")
        BtcAdvanced.warnings(suggestedRate, plan.feeRateSatPerVb).forEach { warning ->
            Spacer(Modifier.height(LenslySpacing.xs))
            InlineBanner(message = warning, tone = BannerTone.Warning)
        }
        Spacer(Modifier.height(LenslySpacing.md))
        WalletPill(
            text = "Advanced",
            onClick = { advanced = true },
            modifier = Modifier.fillMaxWidth(),
            style = WalletPillStyle.Outline,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            "Lumio holds no key. Lensly signs this transaction, and Lumio sends only the signed transaction that Lensly shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
    if (advanced) {
        BtcAdvancedSheet(
            suggestedRate = suggestedRate,
            rate = plan.feeRateSatPerVb,
            onSave = { rate ->
                advanced = false
                onSave(rate)
            },
            onDismiss = { advanced = false },
        )
    }
}

/** The unsigned request as an animated code for Lensly to scan. [notice] is why the last signature did not send. */
@Composable
fun BtcSignRequestScreen(
    network: BtcNetwork,
    parts: List<String>,
    notice: String?,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "SIGN IN LENSLY",
        onBack = onBack,
        scrollable = false,
        bottomAction = {
            WalletPill(
                text = "Scan Lensly's signature",
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        NetworkHeader(network)
        Text(
            "In Lensly, scan this code, check the review and approve. Then scan the signature that Lensly shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        AnimatedQr(parts = parts, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        notice?.let {
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/** Why [text] is not an address of [network], or null when it is one. The words are the ones the send refuses with. */
internal fun btcRecipientError(text: String, network: BtcNetwork): String? = runCatching { BtcSendInput.recipient(text, network) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a valid recipient address." }

/** The account the send pays from: its script type and path, with a TESTNET label on testnet4. It is fixed by the send, so it is not a choice. */
@Composable
private fun AccountLine(ref: BtcAccountRef) {
    val account = ref.account
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                PairLabels.scriptType(account.scriptType),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                account.path.asString('\''),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (account.network == BtcNetwork.TESTNET4) PillChip(text = TESTNET_LABEL)
    }
}

/** The network's name, with a TESTNET label when it is testnet4. */
@Composable
private fun NetworkHeader(network: BtcNetwork) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (network == BtcNetwork.MAINNET) "Bitcoin mainnet" else "Bitcoin testnet4",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (network == BtcNetwork.TESTNET4) {
            Spacer(Modifier.width(LenslySpacing.xs))
            PillChip(text = TESTNET_LABEL)
        }
    }
    Spacer(Modifier.height(LenslySpacing.sm))
}

/** A fee rate as the form takes it: a whole number when it is one, else its decimals. */
internal fun rateText(rate: Double): String = if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()
