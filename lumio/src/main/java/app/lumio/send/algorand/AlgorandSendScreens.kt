package app.lumio.send.algorand

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.components.AddressField
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.algorand.AlgorandFormat
import app.lumio.ui.send.ReviewAddress
import app.lumio.ui.send.ReviewField
import java.math.BigInteger

private const val TESTNET_LABEL = "TESTNET"

/** The recipient step: the address that receives the ALGO, typed or scanned. Continue is the one primary action. */
@Composable
internal fun AlgorandRecipientScreen(
    network: NetworkId,
    recipient: String,
    onRecipient: (String) -> Unit,
    onScan: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val valid = recipient.isNotBlank() && AlgorandSend.recipientError(recipient) == null
    LenslyScreen(
        title = "Send ALGO",
        onBack = onBack,
        bottomAction = {
            WalletPill(
                text = "Continue",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
                enabled = valid,
            )
        },
    ) {
        NetworkHeader(network)
        Text(
            "Enter an Algorand address, or scan one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        AddressField(
            value = recipient,
            onValueChange = onRecipient,
            label = "Recipient address",
            validate = AlgorandSend::recipientError,
            onScan = onScan,
        )
    }
}

/**
 * The amount step: the ALGO to send, a text action that fills in the most that can be sent, and the balance and fee the network gave. [error] is
 * the reason the amount cannot be sent, shown under the field. Review is the one primary action.
 */
@Composable
internal fun AlgorandAmountScreen(
    network: NetworkId,
    toAddress: String,
    balance: BigInteger?,
    feeEstimate: BigInteger?,
    maxEnabled: Boolean,
    amount: String,
    onAmount: (String) -> Unit,
    onMax: () -> Unit,
    error: String?,
    notice: String?,
    canReview: Boolean,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "Amount",
        onBack = onBack,
        bottomAction = {
            WalletPill(
                text = "Review",
                onClick = onReview,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
                enabled = canReview,
            )
        },
    ) {
        NetworkHeader(network)
        ReviewAddress("To", toAddress)
        balance?.let { ReviewField("Balance", AlgorandFormat.amount(it)) }
        Spacer(Modifier.height(LenslySpacing.md))
        val supporting: (@Composable () -> Unit)? = if (error != null) {
            { Text(error) }
        } else {
            null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amount,
                onValueChange = onAmount,
                label = { Text("Amount") },
                suffix = { Text("ALGO") },
                singleLine = true,
                isError = error != null,
                supportingText = supporting,
                enabled = balance != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onMax, enabled = maxEnabled) {
                Text("Max")
            }
        }
        feeEstimate?.let {
            Spacer(Modifier.height(LenslySpacing.sm))
            ReviewField("Network fee", AlgorandFormat.amount(it))
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            "The account keeps 0.1 ALGO. That part is never sent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/**
 * The review: the sentence that says what leaves and to whom, then the addresses, the amount, the fee, the total and the network. Nothing here
 * signs. Lensly signs after the owner scans the request.
 */
@Composable
internal fun AlgorandReviewScreen(
    plan: AlgorandSendPlan,
    notice: String?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "Review",
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
        Text(
            "Send ${AlgorandFormat.amount(plan.amount)} to ${plan.toAddress}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        ReviewAddress("From", plan.fromAddress)
        ReviewAddress("To", plan.toAddress)
        ReviewField("Amount", AlgorandFormat.amount(plan.amount))
        ReviewField("Network fee", AlgorandFormat.amount(plan.fee))
        ReviewField("Total", AlgorandFormat.amount(plan.amount + plan.fee))
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            "Lumio holds no key. Lensly signs this transaction, and Lumio sends only the signature that Lensly shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/** The request as an animated code for Lensly to scan. [notice] is why the last signature did not send. */
@Composable
internal fun AlgorandSignRequestScreen(
    network: NetworkId,
    parts: List<String>,
    notice: String?,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "Sign in Lensly",
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

/**
 * The sent transaction: its ID, what the network says about it now, and the explorer page. [notice] is why the explorer did not open.
 */
@Composable
internal fun AlgorandSentScreen(
    network: NetworkId,
    txid: String,
    status: String,
    notice: String?,
    onOpenExplorer: () -> Unit,
    onDone: () -> Unit,
) {
    LenslyScreen(
        title = "Sent",
        bottomAction = {
            WalletPill(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        NetworkHeader(network)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LenslySpacing.md))
        ReviewField("Transaction id", txid)
        Spacer(Modifier.height(LenslySpacing.md))
        SettingsRow(title = "View on explorer", onClick = onOpenExplorer)
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/** The network's name, with a TESTNET label on testnet. */
@Composable
private fun NetworkHeader(network: NetworkId) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (network.testnet) "Algorand testnet" else "Algorand mainnet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (network.testnet) {
            Spacer(Modifier.width(LenslySpacing.xs))
            PillChip(text = TESTNET_LABEL)
        }
    }
    Spacer(Modifier.height(LenslySpacing.sm))
}
