package app.lumio.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressChain
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.addressNotesOf
import app.lensly.app.headline
import app.lensly.core.registry.Amounts
import app.lensly.ui.components.AddressField
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.qr.AnimatedQr
import app.lumio.ui.scan.LumioScanScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.evm.EvmAdvanced
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmFormat
import app.lumio.evm.PreparedSend
import app.lumio.evm.SendAsset
import app.lumio.evm.SendInput
import app.lumio.ui.kit.LumioIsland
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTag
import app.lumio.ui.kit.LumioTextAction

private const val TESTNET_LABEL = "Testnet"

/** What the recipient scan says before a code is read. It stays on screen while the camera looks for one. */
internal const val RECIPIENT_SCAN_HINT = "Point the camera at an address or a payment code."

/**
 * The Details step of a send: the address that sends, the network the send is bound to (the account fixes it, so there is no picker), the asset of
 * that network (the native coin or a token the address holds), the recipient and the amount in that asset, typed on the system's decimal keyboard.
 * [chain] is null when that network is off, and the form then says so. [notice] is why the last attempt did not go through. [busy] holds the review
 * until the endpoint has answered.
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
    SendStepScaffold(
        step = SEND_STEP_DETAILS,
        title = if (chain == null) "Send" else "Send ${selectedAsset?.symbol ?: chain.symbol}",
        onBack = onBack,
        bottomAction = {
            LumioPrimaryButton(text = "Review", onClick = onReview, enabled = !busy && chain != null)
        },
    ) {
        if (chain == null) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "Network is off",
                body = "Turn it on in EVM settings to send on it.",
            )
            return@SendStepScaffold
        }
        if (busy) StepLabel("Reading the network")
        StepLabel("From")
        Text(fromLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AddressText(
            address = fromAddress,
            shown = fromAddress,
            icon = false,
            style = MaterialTheme.typography.bodyMedium,
        )

        StepLabel("Network")
        ChainLine(chain = chain)

        StepLabel("Asset")
        LumioIsland {
            assets.forEach { asset ->
                AssetRow(asset = asset, selected = asset.token?.contract == selectedAsset?.token?.contract, onSelect = { onSelectAsset(asset) })
            }
        }

        StepLabel("Send to")
        AddressField(
            value = recipient,
            onValueChange = onRecipient,
            label = "Recipient address (0x)",
            validate = ::recipientError,
            enabled = !busy,
            onScan = onScan,
        )

        StepLabel("Amount in ${selectedAsset?.symbol ?: chain.symbol}")
        AmountField(value = amount, onValueChange = onAmount, enabled = !busy)
        selectedAsset?.let {
            Text(assetDetail(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/**
 * The review of a send before anything is requested: one sentence of what leaves and where it goes, then the parties and the most the fee can be.
 * [suggested] is the send the network prepared; Advanced edits the nonce, the gas limit and the fees, and [onSave] takes the edited send.
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
    /** The Ethereum accounts of the paired wallets, so the sending and receiving accounts are named. */
    accounts: Map<String, InternalAccount> = emptyMap(),
) {
    val chain = send.chain
    val token = send.token
    var advanced by remember { mutableStateOf(false) }
    val amount = if (token == null) {
        EvmFormat.amount(send.valueWei, chain.decimals, chain.symbol)
    } else {
        EvmFormat.amount(send.valueWei, token.decimals, token.symbol)
    }
    val recipient = addressNotesOf(send.to, AddressChain.EVM, accounts, addressBook)?.headline() ?: send.to
    SendStepScaffold(
        step = SEND_STEP_REVIEW,
        title = "Review send",
        onBack = onBack,
        bottomAction = {
            LumioPrimaryButton(text = "Show to Lensly", onClick = onConfirm)
        },
    ) {
        NetworkHeader(chain)
        Text(
            "Send $amount to $recipient",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        LumioIsland {
            Column(modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xxs)) {
                ReviewAddress("From", send.from, notes = addressNotesOf(send.from, AddressChain.EVM, accounts, addressBook))
                ReviewAddress("To", send.to, notes = addressNotesOf(send.to, AddressChain.EVM, accounts, addressBook))
                ReviewField("Amount", amount)
                if (token != null) ReviewAddress("Token contract", send.tx.to ?: token.contract)
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        LumioIsland {
            Column(modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xxs)) {
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
            }
        }
        EvmAdvanced.warnings(suggested, send).forEach { warning ->
            Spacer(Modifier.height(LenslySpacing.xs))
            InlineBanner(message = warning, tone = BannerTone.Warning)
        }
        LumioTextAction(text = "Advanced", onClick = { advanced = true }, modifier = Modifier.padding(top = LenslySpacing.xs))
        Spacer(Modifier.height(LenslySpacing.xs))
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
    LumioScanScreen(title = "Scan address", progress = null, statusText = status, onText = onText, onCancel = onCancel)
}

/**
 * The request as an animated code for Lensly to scan, on a white card so that the code reads on either palette. [notice] is why the last signature
 * did not send.
 */
@Composable
fun SignRequestScreen(
    send: PreparedSend,
    parts: List<String>,
    notice: String?,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    SendStepScaffold(
        step = SEND_STEP_SIGN,
        title = "Sign in Lensly",
        onBack = onBack,
        scrollable = false,
        bottomAction = {
            LumioPrimaryButton(text = "Scan Lensly's signature", onClick = onScan)
        },
    ) {
        NetworkHeader(send.chain)
        Text(
            "On Lensly, tap Scan and point it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(Color.White)
                .padding(LenslySpacing.md),
        ) {
            AnimatedQr(parts = parts, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.weight(1f))
        notice?.let {
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/** The camera scan of Lensly's signature. [progress] shows for an animated code, and [status] says what the scan is doing. */
@Composable
fun SignatureScanScreen(progress: Float?, status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    LumioScanScreen(
        title = "Scan signature",
        progress = progress,
        statusText = status,
        onText = onText,
        onCancel = onCancel,
    )
}

/** The network the send is on: its name, with a testnet tag when it is a testnet. The send offers no other network. */
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
        if (chain.testnet) LumioTag(text = TESTNET_LABEL)
    }
}

/**
 * The amount: the figure is set large where the owner reads it, and it is typed on the system's decimal keyboard. There is no keypad and no preset
 * amount.
 */
@Composable
internal fun AmountField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val figure = MaterialTheme.typography.displaySmall
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.sm),
        enabled = enabled,
        singleLine = true,
        textStyle = figure.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { field ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (value.isEmpty()) {
                    Text("0", style = figure, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}

/** One coin of the asset picker: its symbol, and what the sending address holds of it. */
@Composable
private fun AssetRow(asset: SendAsset, selected: Boolean, onSelect: () -> Unit) {
    SendAssetRow(symbol = asset.symbol, detail = assetDetail(asset), selected = selected, onSelect = onSelect)
}

/** What the picker says under a coin: the native coin, the token's balance on this address, or that none is held. */
private fun assetDetail(asset: SendAsset): String {
    val token = asset.token ?: return "Native coin"
    val balance = asset.balance ?: return "No balance on this address"
    return "Balance " + EvmFormat.amount(balance, token.decimals, token.symbol)
}

/** The chain's name, with a testnet tag when it is a testnet. */
@Composable
private fun NetworkHeader(chain: EvmChain) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(chain.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (chain.testnet) {
            Spacer(Modifier.width(LenslySpacing.xs))
            LumioTag(text = TESTNET_LABEL)
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A [ReviewField] whose value is an address: the same label and text, with the copy action. The name the address goes by is shown above
 * it, so the owner sees whom it is for: an account of this device as "Your account", else the Address Book name. An address that is both
 * also shows its Address Book name below, as "Address name".
 */
@Composable
internal fun ReviewAddress(label: String, address: String, notes: AddressNotes? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        notes?.headline()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        if (notes?.internal != null) notes.entry?.let { entry -> ReviewField("Address name", entry.name) }
        AddressText(address = address, shown = address, icon = false, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Why [text] is not a recipient the EVM send can pay, or null when it is one. The words are the ones the send refuses with. */
internal fun recipientError(text: String): String? = runCatching { SendInput.recipient(text) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a valid recipient address." }

private const val GWEI_DECIMALS = 9
