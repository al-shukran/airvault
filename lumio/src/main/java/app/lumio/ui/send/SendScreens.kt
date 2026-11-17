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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.app.AddressBookEntry
import app.lensly.app.addressNotesOf
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
import app.lumio.evm.EvmAdvanced
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmFormat
import app.lumio.evm.PreparedSend
import app.lumio.evm.SendAsset
import app.lumio.evm.SendInput

private const val TESTNET_LABEL = "TESTNET"

/** What the recipient scan says before a code is read. It stays on screen while the camera looks for one. */
internal const val RECIPIENT_SCAN_HINT = "Point the camera at an address or a payment code."

/**
 * The send form: the address that sends, the network the send is bound to (the account fixes it, so there is no picker), the asset of that network
 * (the native coin or a token the address holds), the recipient and the amount in that asset. [chain] is null when that network is off, and the form
 * then says so. [notice] is why the last attempt did not go through. [busy] holds the review until the endpoint has answered.
 */
@Composable
fun SendFormScreen(
    fromLabel: String,
    fromAddress: String,
    chain: EvmChain?,
    assets: List<SendAsset>,
    selectedAsset: SendAsset?,
    onSelectAsset: (SendAsset) -> Unit,
    recipient: String,
    onRecipient: (String) -> Unit,
    onScan: () -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    busy: Boolean,
    notice: String?,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "SEND",
        onBack = onBack,
        bottomAction = {
            WalletPill(
                text = "Review",
                onClick = onReview,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
                enabled = !busy && chain != null,
            )
        },
    ) {
        if (chain == null) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "Network is off",
                body = "Turn it on in EVM settings to send on it.",
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
        ChainLine(chain = chain)

        SectionLabel("Asset")
        assets.forEach { asset ->
            AssetRow(asset = asset, selected = asset.token?.contract == selectedAsset?.token?.contract, onSelect = { onSelectAsset(asset) })
        }

        SectionLabel("Send to")
        AddressField(
            value = recipient,
            onValueChange = onRecipient,
            label = "Recipient address (0x)",
            validate = ::recipientError,
            enabled = !busy,
            onScan = onScan,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        OutlinedTextField(
            value = amount,
            onValueChange = onAmount,
            label = { Text("Amount in ${selectedAsset?.symbol ?: chain.symbol}") },
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

/**
 * The review of a send before anything is requested: what leaves, where it goes, and the most the fee can be. [suggested] is the send the
 * network prepared; Advanced edits the nonce, the gas limit and the fees, and [onSave] takes the edited send.
 */
@Composable
fun SendReviewScreen(
    send: PreparedSend,
    suggested: PreparedSend,
    notice: String?,
    onSave: (PreparedSend) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    addressBook: List<AddressBookEntry> = emptyList(),
) {
    val chain = send.chain
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
        NetworkHeader(chain)
        val token = send.token
        if (token != null) {
            Text(
                "Send ${EvmFormat.amount(send.valueWei, token.decimals, token.symbol)} to ${send.to}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        ReviewAddress("From", send.from)
        ReviewAddress("To", send.to, name = addressNotesOf(send.to, emptyMap(), addressBook)?.entry?.name)
        if (token == null) {
            ReviewField("Amount", EvmFormat.amount(send.valueWei, chain.decimals, chain.symbol))
        } else {
            ReviewField("Amount", EvmFormat.amount(send.valueWei, token.decimals, token.symbol))
            ReviewAddress("Token contract", send.tx.to ?: token.contract)
        }
        ReviewField("Fee, at most", EvmFormat.amount(send.maxFeeWei, chain.decimals, chain.symbol))
        ReviewField("Fee cap per gas", "${Amounts.format(send.maxFeePerGas, GWEI_DECIMALS)} gwei")
        ReviewField("Priority fee per gas", "${Amounts.format(send.maxPriorityFeePerGas, GWEI_DECIMALS)} gwei")
        ReviewField("Gas limit", send.gasLimit.toString())
        ReviewField("Nonce", send.nonce.toString())
        if (token == null) {
            ReviewField("Balance", EvmFormat.amount(send.balanceWei, chain.decimals, chain.symbol))
        } else {
            ReviewField("Balance", send.tokenBalance?.let { EvmFormat.amount(it, token.decimals, token.symbol) }.orEmpty())
            ReviewField("Gas balance", EvmFormat.amount(send.balanceWei, chain.decimals, chain.symbol))
        }
        EvmAdvanced.warnings(suggested, send).forEach { warning ->
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
            "Lumio holds no key. Lensly signs this transaction, and Lumio sends only the signature that Lensly shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
    if (advanced) {
        AdvancedSendSheet(
            suggested = suggested,
            current = send,
            onSave = { edited ->
                advanced = false
                onSave(edited)
            },
            onDismiss = { advanced = false },
        )
    }
}

/** The camera scan of a recipient: an address or a payment code. [status] says what to scan, or why the last code was refused. */
@Composable
fun RecipientScanScreen(status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    ScanScreen(title = "SCAN ADDRESS", progress = null, statusText = status, onText = onText, onCancel = onCancel)
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

/** The network the send is on: its name, with a TESTNET label when it is a testnet. The send offers no other network. */
@Composable
private fun ChainLine(chain: EvmChain) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            chain.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (chain.testnet) PillChip(text = TESTNET_LABEL)
    }
}

/** One coin of the asset picker: its symbol, and what the sending address holds of it. */
@Composable
private fun AssetRow(asset: SendAsset, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(LenslySpacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(asset.symbol, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                assetDetail(asset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the picker says under a coin: the native coin, the token's balance on this address, or that none is held. */
private fun assetDetail(asset: SendAsset): String {
    val token = asset.token ?: return "Native coin"
    val balance = asset.balance ?: return "No balance on this address"
    return "Balance " + EvmFormat.amount(balance, token.decimals, token.symbol)
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

/**
 * A [ReviewField] whose value is an address: the same label and text, with the copy action. A [name] from the Address Book is shown above
 * the address, so the owner sees whom it is for.
 */
@Composable
internal fun ReviewAddress(label: String, address: String, name: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        name?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        AddressText(address = address, shown = address, icon = false, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Why [text] is not a recipient the EVM send can pay, or null when it is one. The words are the ones the send refuses with. */
internal fun recipientError(text: String): String? = runCatching { SendInput.recipient(text) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a valid recipient address." }

private const val GWEI_DECIMALS = 9
