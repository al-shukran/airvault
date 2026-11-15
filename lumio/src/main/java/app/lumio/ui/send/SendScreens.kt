package app.lumio.ui.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.core.registry.Amounts
import app.lensly.ui.components.AddressField
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.components.PillChip
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.qr.ScanScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmFormat
import app.lumio.evm.PreparedSend
import app.lumio.evm.SendInput

private const val TESTNET_LABEL = "TESTNET"

/**
 * The send form: the address that sends, the chain (only the chains that are on), the recipient and the amount. [notice] is why the last
 * attempt did not go through. [busy] holds the review until the endpoint has answered.
 */
@Composable
fun SendFormScreen(
    fromLabel: String,
    fromAddress: String,
    chains: List<EvmChain>,
    selectedChainId: Long?,
    onSelectChain: (Long) -> Unit,
    recipient: String,
    onRecipient: (String) -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    busy: Boolean,
    notice: String?,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    val selected = chains.firstOrNull { it.chainId == selectedChainId } ?: chains.firstOrNull()
    LenslyScreen(
        title = "SEND",
        onBack = onBack,
        bottomAction = {
            WalletPill(
                text = "Review",
                onClick = onReview,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
                enabled = !busy && selected != null,
            )
        },
    ) {
        if (chains.isEmpty()) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No chains are on",
                body = "Turn a chain on in EVM settings to send on it.",
            )
            return@LenslyScreen
        }
        if (busy) {
            Text(
                "Reading the network",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("From")
        Text(fromLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AddressText(
            address = fromAddress,
            shown = fromAddress,
            icon = false,
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionLabel("Network")
        chains.forEach { chain ->
            ChainRow(chain = chain, selected = chain.chainId == selected?.chainId, onSelect = { onSelectChain(chain.chainId) })
        }

        SectionLabel("Send to")
        AddressField(
            value = recipient,
            onValueChange = onRecipient,
            label = "Recipient address (0x)",
            validate = ::recipientError,
            enabled = !busy,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        OutlinedTextField(
            value = amount,
            onValueChange = onAmount,
            label = { Text("Amount in ${selected?.symbol.orEmpty()}") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/** The review of a send before anything is requested: what leaves, where it goes, and the most the fee can be. */
@Composable
fun SendReviewScreen(
    send: PreparedSend,
    notice: String?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val chain = send.chain
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
        NetworkHeader(chain)
        ReviewAddress("From", send.from)
        ReviewAddress("To", send.to)
        ReviewField("Amount", EvmFormat.amount(send.valueWei, chain.decimals, chain.symbol))
        ReviewField("Fee, at most", EvmFormat.amount(send.maxFeeWei, chain.decimals, chain.symbol))
        ReviewField("Fee cap per gas", "${Amounts.format(send.maxFeePerGas, GWEI_DECIMALS)} gwei")
        ReviewField("Priority fee per gas", "${Amounts.format(send.maxPriorityFeePerGas, GWEI_DECIMALS)} gwei")
        ReviewField("Gas limit", send.gasLimit.toString())
        ReviewField("Nonce", send.nonce.toString())
        ReviewField("Balance", EvmFormat.amount(send.balanceWei, chain.decimals, chain.symbol))
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
fun SignRequestScreen(
    send: PreparedSend,
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
        NetworkHeader(send.chain)
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

/** The camera scan of Lensly's signature. [progress] shows for an animated code, and [status] says what the scan is doing. */
@Composable
fun SignatureScanScreen(progress: Float?, status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    ScanScreen(
        title = "SCAN SIGNATURE",
        progress = progress,
        statusText = status,
        onText = onText,
        onCancel = onCancel,
    )
}

/**
 * The broadcast transaction: its hash, and the explorer page when the chain has one. [notice] is why the explorer did not open.
 */
@Composable
fun SendSentScreen(
    chain: EvmChain,
    hash: String,
    explorerUrl: String?,
    notice: String?,
    onOpenExplorer: () -> Unit,
    onDone: () -> Unit,
) {
    LenslyScreen(
        title = "SENT",
        bottomAction = {
            WalletPill(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        NetworkHeader(chain)
        ReviewField("Transaction hash", hash)
        if (explorerUrl != null) {
            Spacer(Modifier.height(LenslySpacing.md))
            SettingsRow(title = "View on explorer", onClick = onOpenExplorer)
        }
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

@Composable
private fun ChainRow(chain: EvmChain, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            chain.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (chain.testnet) PillChip(text = TESTNET_LABEL)
    }
}

/** The chain's name, with a TESTNET label when it is a testnet. */
@Composable
private fun NetworkHeader(chain: EvmChain) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(chain.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (chain.testnet) {
            Spacer(Modifier.width(LenslySpacing.xs))
            PillChip(text = TESTNET_LABEL)
        }
    }
    Spacer(Modifier.height(LenslySpacing.sm))
}

@Composable
internal fun SectionLabel(text: String) {
    Spacer(Modifier.height(LenslySpacing.md))
    WalletEyebrow(text)
    Spacer(Modifier.height(LenslySpacing.xs))
}

@Composable
internal fun ReviewField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A [ReviewField] whose value is an address: the same label and text, with the copy action. */
@Composable
internal fun ReviewAddress(label: String, address: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AddressText(address = address, shown = address, icon = false, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Why [text] is not a recipient the EVM send can pay, or null when it is one. The words are the ones the send refuses with. */
internal fun recipientError(text: String): String? = runCatching { SendInput.recipient(text) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a valid recipient address." }

private const val GWEI_DECIMALS = 9
