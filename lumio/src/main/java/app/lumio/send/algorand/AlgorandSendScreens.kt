package app.lumio.send.algorand

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.lensly.ui.components.AddressField
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.algorand.AlgorandFormat
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTag
import app.lumio.ui.send.AmountField
import app.lumio.ui.send.ReviewAddress
import app.lumio.ui.send.ReviewField
import app.lumio.ui.send.SEND_STEP_DETAILS
import app.lumio.ui.send.SEND_STEP_REVIEW
import app.lumio.ui.send.SEND_STEP_SIGN
import app.lumio.ui.send.SendAssetRow
import app.lumio.ui.send.SendStepScaffold
import app.lumio.ui.send.StepLabel

/**
 * The Details step of an Algorand send, on the Lumen kit: the network, the coin that the send moves, then the recipient and the amount in that coin.
 * [assets] are the coins the account can choose, and [selected] is the one the send moves. [balanceText] is the balance of the coin, [keepText] the ALGO
 * the account keeps, [feeText] the network fee, and [amountError] why the amount cannot be sent. [busy] holds the form until the network has answered.
 */
@Composable
internal fun AlgorandSendFormScreen(
    network: NetworkId,
    fromAddress: String,
    assets: List<AlgorandAsset>,
    selected: AlgorandAsset,
    onSelect: (AlgorandAsset) -> Unit,
    balanceText: String?,
    keepText: String?,
    recipient: String,
    onRecipient: (String) -> Unit,
    onScan: () -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    maxEnabled: Boolean,
    onMax: () -> Unit,
    feeText: String?,
    amountError: String?,
    busy: Boolean,
    notice: String?,
    canReview: Boolean,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    val symbol = selected.symbol
    SendStepScaffold(
        step = SEND_STEP_DETAILS,
        title = "Send $symbol",
        onBack = onBack,
        bottomAction = { LumioPrimaryButton(text = "Review", onClick = onReview, enabled = canReview) },
    ) {
        if (busy) StepLabel("Reading the network")
        StepLabel("From")
        AddressText(address = fromAddress, icon = false, style = MaterialTheme.typography.bodyMedium)

        StepLabel("Network")
        NetworkLine(network)

        StepLabel("Asset")
        LumioIsland {
            assets.forEach { asset ->
                SendAssetRow(
                    symbol = asset.symbol,
                    detail = assetDetail(asset),
                    selected = asset.key == selected.key,
                    onSelect = { onSelect(asset) },
                )
            }
        }

        StepLabel("Send to")
        AddressField(
            value = recipient,
            onValueChange = onRecipient,
            label = "Recipient Address",
            validate = AlgorandSend::recipientError,
            enabled = !busy,
            onScan = onScan,
        )
        StepLabel("Amount in $symbol")
        AmountField(value = amount, onValueChange = onAmount, enabled = !busy && balanceText != null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                balanceText?.let { "Balance $it" }.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LumioTextAction(text = "Use Max", onClick = onMax, enabled = maxEnabled)
        }
        if (selected.assetId == null && keepText != null) {
            Text(
                "The account keeps $keepText ALGO. That part is never sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        feeText?.let {
            Text(
                "Network fee $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        amountError?.let {
            Spacer(Modifier.height(LenslySpacing.xs))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/**
 * The review: one sentence of what leaves and to whom, or of what the opt-in does, then the sender, the recipient, the amount and the fee. Nothing here
 * signs. Lensly signs after the owner scans the request. [symbol] and [decimals] name the coin of an asset send.
 */
@Composable
internal fun AlgorandReviewScreen(
    plan: AlgorandSendPlan,
    symbol: String,
    decimals: Int,
    notice: String?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val optIn = plan is AsaSendPlan && plan.isOptIn
    val amount = AlgorandAssets.amountText(plan, symbol, decimals)
    SendStepScaffold(
        step = SEND_STEP_REVIEW,
        title = "Review Send",
        onBack = onBack,
        bottomAction = { LumioPrimaryButton(text = "Show to Lensly", onClick = onConfirm) },
    ) {
        NetworkHeader(plan.network)
        Text(
            if (optIn) "Opt in to $symbol" else "Send $amount to ${plan.toAddress}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        LumioIsland {
            Column(modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xxs)) {
                ReviewAddress("From", plan.fromAddress)
                if (optIn) {
                    ReviewField("Asset", symbol)
                } else {
                    ReviewAddress("To", plan.toAddress)
                    ReviewField("Amount", amount)
                }
                ReviewField("Network Fee", AlgorandFormat.amount(plan.fee))
                if (plan is AlgorandPaymentPlan) ReviewField("Total", AlgorandFormat.amount(plan.amount + plan.fee))
            }
        }
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

/** The request as an animated code for Lensly to scan, on a white card so that the code reads on either palette. [notice] is why the last signature did not send. */
@Composable
internal fun AlgorandSignRequestScreen(
    network: NetworkId,
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
        bottomAction = { LumioPrimaryButton(text = "Scan Lensly's Signature", onClick = onScan) },
    ) {
        NetworkHeader(network)
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

/** An opt-in while its asset is read from the network: the title, and the words why the read failed when it did. */
@Composable
internal fun AlgorandOptInReadingScreen(notice: String?, onBack: () -> Unit) {
    SendStepScaffold(
        step = SEND_STEP_DETAILS,
        title = "Opt In to Asset",
        onBack = onBack,
        bottomAction = {},
    ) {
        if (notice == null) StepLabel("Reading the network") else InlineBanner(message = notice, tone = BannerTone.Error)
    }
}

/** What the picker says under a coin: the native coin, or the balance that the account holds. */
private fun assetDetail(asset: AlgorandAsset): String = when (val held = asset.held) {
    null -> "Native Coin"
    else -> "Balance ${AlgorandAssets.text(held, asset.decimals)} ${asset.symbol}"
}

private fun networkName(network: NetworkId): String = if (network.testnet) "Algorand Testnet" else "Algorand Mainnet"

/** The network the send is on, with a Testnet tag on testnet, as a row of the Details step. */
@Composable
private fun NetworkLine(network: NetworkId) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            networkName(network),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (network.testnet) LumioTag(text = "Testnet")
    }
}

/** The network's name as a title, with a Testnet tag on testnet. */
@Composable
private fun NetworkHeader(network: NetworkId) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(networkName(network), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (network.testnet) {
            Spacer(Modifier.width(LenslySpacing.xs))
            LumioTag(text = "Testnet")
        }
    }
    Spacer(Modifier.height(LenslySpacing.sm))
}
