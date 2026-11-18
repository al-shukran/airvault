package app.lumio.ui.send.solana

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
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.net.NetworkId
import app.lumio.send.solana.SOLANA_FEE_LAMPORTS
import app.lumio.send.solana.SOLANA_RENT_RESERVE_LAMPORTS
import app.lumio.send.solana.SolanaAssets
import app.lumio.send.solana.SolanaOutcome
import app.lumio.send.solana.SolanaSendAsset
import app.lumio.send.solana.SolanaSendInput
import app.lumio.send.solana.SolanaSendPlan
import app.lumio.ui.kit.LumioIsland
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTag
import app.lumio.ui.kit.LumioTextAction
import app.lumio.ui.send.AmountField
import app.lumio.ui.send.ReviewAddress
import app.lumio.ui.send.ReviewField
import app.lumio.ui.send.SEND_STEP_DETAILS
import app.lumio.ui.send.SEND_STEP_REVIEW
import app.lumio.ui.send.SEND_STEP_SIGN
import app.lumio.ui.send.SendAssetRow
import app.lumio.ui.send.SendStepScaffold
import app.lumio.ui.send.StepLabel
import java.math.BigInteger

/**
 * The Details step of a Solana send: the account the send comes from, which is fixed, the coin that the send moves (SOL, or a token the account holds), the
 * recipient, and the amount in that coin with a Use max text action. [balanceText] is the balance of the coin. [busy] holds the review while the network
 * is read, and [notice] is why the last attempt did not go through.
 */
@Composable
internal fun SolanaSendFormScreen(
    network: NetworkId,
    sender: String,
    assets: List<SolanaSendAsset>,
    selected: SolanaSendAsset,
    onSelect: (SolanaSendAsset) -> Unit,
    balanceText: String?,
    recipient: String,
    onRecipient: (String) -> Unit,
    onScan: () -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    maxEnabled: Boolean,
    onMax: () -> Unit,
    busy: Boolean,
    notice: String?,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    SendStepScaffold(
        step = SEND_STEP_DETAILS,
        title = "Send ${selected.symbol}",
        onBack = onBack,
        bottomAction = { LumioPrimaryButton(text = "Review", onClick = onReview, enabled = !busy) },
    ) {
        if (busy) StepLabel("Reading the network")
        StepLabel("From")
        AddressText(address = sender, shown = sender, icon = false, style = MaterialTheme.typography.bodyMedium)

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
            validate = ::recipientError,
            enabled = !busy,
            onScan = onScan,
        )

        StepLabel("Amount in ${selected.symbol}")
        AmountField(value = amount, onValueChange = onAmount, enabled = !busy && balanceText != null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                balanceText?.let { "Balance $it" }.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LumioTextAction(text = "Use Max", onClick = onMax, enabled = !busy && maxEnabled)
        }
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/**
 * The review of a send before Lensly is asked to sign it: a sentence that says what leaves, then the rows the owner checks. [symbol] names the coin of a
 * token send. Lensly keeps the rent reserve in the sender, so the account stays open.
 */
@Composable
internal fun SolanaSendReviewScreen(plan: SolanaSendPlan, symbol: String, notice: String?, onConfirm: () -> Unit, onBack: () -> Unit) {
    val token = plan.token
    val lamports = BigInteger.valueOf(plan.lamports)
    val fee = BigInteger.valueOf(plan.feeLamports)
    val amount = if (token == null) {
        "${SolanaSendInput.format(lamports)} SOL"
    } else {
        "${SolanaAssets.text(token.amount, token.decimals)} $symbol"
    }
    SendStepScaffold(
        step = SEND_STEP_REVIEW,
        title = "Review Send",
        onBack = onBack,
        bottomAction = { LumioPrimaryButton(text = "Show to Lensly", onClick = onConfirm) },
    ) {
        NetworkHeader(plan.network)
        Text(
            "Send $amount to ${shortAddress(plan.recipientAddress)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        LumioIsland {
            Column(modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xxs)) {
                ReviewAddress("From", plan.senderAddress)
                ReviewAddress("To", plan.recipientAddress)
                ReviewField("Amount", amount)
                ReviewField("Network Fee", "${SolanaSendInput.format(fee)} SOL, ${plan.feeLamports} lamports")
                if (token == null) ReviewField("Total", "${SolanaSendInput.format(lamports + fee)} SOL")
                ReviewField("Network", networkName(plan.network))
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            "Lensly keeps ${SolanaSendInput.format(BigInteger.valueOf(SOLANA_RENT_RESERVE_LAMPORTS))} SOL in this account, so it stays open. " +
                "Lumio holds no key. Lensly signs this, and Lumio sends only the signature that Lensly shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/**
 * The review of the one-time setup of offline signing, before Lensly is asked to sign it. The setup creates the nonce account of [sender] and pays
 * its rent into it from [sender]. Once it is confirmed, every send from [sender] names that nonce instead of a blockhash that expires.
 */
@Composable
internal fun SolanaSetupReviewScreen(
    network: NetworkId,
    sender: String,
    nonceAccount: String,
    rentLamports: Long,
    notice: String?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    SendStepScaffold(
        step = SEND_STEP_REVIEW,
        title = "Offline Signing",
        onBack = onBack,
        bottomAction = { LumioPrimaryButton(text = "Show to Lensly", onClick = onConfirm) },
    ) {
        NetworkHeader(network)
        Text(
            "Create the nonce account for this account. You do this once.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        LumioIsland {
            Column(modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xxs)) {
                ReviewAddress("Account", sender)
                ReviewAddress("Nonce Account", nonceAccount)
                ReviewField("Rent, paid into the nonce account", "${SolanaSendInput.format(BigInteger.valueOf(rentLamports))} SOL")
                ReviewField(
                    "Network Fee",
                    "${SolanaSendInput.format(BigInteger.valueOf(SOLANA_FEE_LAMPORTS))} SOL, $SOLANA_FEE_LAMPORTS lamports",
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            "Offline signing lets this account send SOL without a blockhash that could expire while Lensly reviews the send. " +
                "Lensly signs this setup within about 20 seconds, because the request names a recent blockhash that expires. " +
                "If it expires, start the setup again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/** What the one-time setup's request screen says: what the owner does, and how soon the blockhash in the code runs out. */
internal const val SETUP_SIGN_TEXT = "One-time setup. Scan with Lensly and sign within about 20 seconds."

/**
 * The request as an animated code for Lensly to scan, on a white card so that the code reads on either palette. [notice] is why the last signature did not
 * send. A [setup] request says so in plain words and gives the time the setup's blockhash allows.
 */
@Composable
internal fun SolanaSignRequestScreen(parts: List<String>, notice: String?, onScan: () -> Unit, onBack: () -> Unit, setup: Boolean = false) {
    SendStepScaffold(
        step = SEND_STEP_SIGN,
        title = "Sign in Lensly",
        onBack = onBack,
        scrollable = false,
        bottomAction = { LumioPrimaryButton(text = "Scan Lensly's Signature", onClick = onScan) },
    ) {
        Text(
            if (setup) SETUP_SIGN_TEXT else "In Lensly, scan this code, check the review and approve. Then scan the signature that Lensly shows.",
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

/** Why [text] is not a recipient the Solana send can pay, or null when it is one. The words are the ones the send refuses with. */
internal fun recipientError(text: String): String? = runCatching { SolanaSendInput.recipientAddress(text) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a Solana address." }

/** What the picker says under a coin: the native coin, or the balance of the token that the account holds. */
private fun assetDetail(asset: SolanaSendAsset): String {
    val token = asset.token ?: return "Native Coin"
    return "Balance ${SolanaAssets.text(token.amount, asset.decimals)} ${asset.symbol}"
}

private fun networkName(network: NetworkId): String = if (network.testnet) "Devnet" else "Mainnet-beta"

/** The network the send is on, with a Testnet tag on the devnet, as a row of the Details step. */
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

/** The network's name as a title, with a Testnet tag on the devnet. */
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

internal fun solanaOutcomeText(network: NetworkId, outcome: SolanaOutcome?): String = when (outcome) {
    null -> "Checking the network."
    SolanaOutcome.CONFIRMED -> "Confirmed on ${networkName(network)}."
    SolanaOutcome.FAILED -> "The network ran this transaction, and it failed."
    SolanaOutcome.PENDING -> "Not confirmed yet. Check the explorer in a minute, with this signature."
}

/** The first and last characters of an address, with the middle left out. The review shows the whole address in its rows. */
private fun shortAddress(address: String): String = "${address.take(6)}…${address.takeLast(4)}"
