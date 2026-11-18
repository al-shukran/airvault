package app.lumio.ui.send

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion
import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.BtcSendPlan
import app.lumio.btc.btcText
import app.lumio.data.provider.algorand.AlgorandFormat
import app.lumio.evm.EvmFormat
import app.lumio.evm.PreparedSend
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.send.algorand.AlgorandAssets
import app.lumio.send.algorand.AlgorandSendPlan
import app.lumio.send.algorand.AsaSendPlan
import app.lumio.send.solana.SolanaAssets
import app.lumio.send.solana.SolanaSendInput
import app.lumio.send.solana.SolanaSendPlan
import app.lumio.ui.kit.LumioIsland
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTextAction
import app.lumio.ui.kit.rememberLumioHaptics
import app.lumio.ui.pair.PairLabels
import java.math.BigInteger

/** One line of what a signed transaction does, as the owner reads it. */
class SummaryRow(val label: String, val value: String)

/**
 * The last step of every send, on every chain: the signed transaction summarised, with one primary action. Nothing is sent until the owner taps
 * Submit. A refusal shows the network's words, with Try again, which sends the same signed bytes. Once the network has the transaction, the screen
 * shows the success mark, its id, which copies on a tap, with the network's [status] when it reports one, and the explorer page when the chain has one.
 */
@Composable
fun SubmitScreen(
    machine: SubmitMachine<*, String>,
    rows: List<SummaryRow>,
    explorerUrl: (String) -> String?,
    status: String?,
    explorerNotice: String?,
    onSubmit: () -> Unit,
    onOpenExplorer: (String) -> Unit,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val state by machine.state.collectAsState()
    val submitting = state is SubmitState.Submitting
    val sent = state is SubmitState.Sent
    val failed = state is SubmitState.Failed
    val haptics = rememberLumioHaptics()
    LaunchedEffect(sent) { if (sent) haptics.success() }
    LaunchedEffect(failed) { if (failed) haptics.failure() }
    SendStepScaffold(
        step = SEND_STEP_SEND,
        title = if (sent) "Sent" else "Submit",
        onBack = if (submitting) null else onBack,
        bottomAction = {
            when (val current = state) {
                SubmitState.Idle -> LumioPrimaryButton(text = "Submit", onClick = onSubmit)
                SubmitState.Submitting -> LumioPrimaryButton(text = "Submitting", onClick = {}, enabled = false)
                is SubmitState.Failed -> if (current.retryable) {
                    LumioPrimaryButton(text = "Try again", onClick = onSubmit)
                } else {
                    LumioPrimaryButton(text = "Start a new send", onClick = onStartOver)
                }
                is SubmitState.Sent -> LumioPrimaryButton(text = "Done", onClick = onDone)
            }
        },
    ) {
        when (val current = state) {
            SubmitState.Idle -> Text(
                "Lensly signed this transaction. Nothing is sent until you tap Submit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SubmitState.Submitting -> Text(
                "Sending to the network. Keep this screen open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            is SubmitState.Failed -> Text(
                if (current.retryable) "Try again sends the same signed transaction. Nothing was sent." else "The signed transaction cannot be sent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            is SubmitState.Sent -> SentMark()
        }
        Spacer(Modifier.height(LenslySpacing.md))
        LumioIsland {
            Column(modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xxs)) {
                rows.forEach { ReviewField(it.label, it.value) }
            }
        }
        when (val current = state) {
            SubmitState.Idle, SubmitState.Submitting -> Unit
            is SubmitState.Failed -> {
                Spacer(Modifier.height(LenslySpacing.md))
                InlineBanner(message = current.message, tone = BannerTone.Error)
            }
            is SubmitState.Sent -> {
                val id = current.id
                Spacer(Modifier.height(LenslySpacing.md))
                LumioIsland {
                    Column(modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xxs)) {
                        ReviewLabel("Transaction ID")
                        AddressText(address = id, shown = id, icon = false)
                    }
                }
                status?.let {
                    Spacer(Modifier.height(LenslySpacing.sm))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (explorerUrl(id) != null) {
                    LumioTextAction(
                        text = "View on explorer",
                        onClick = { onOpenExplorer(id) },
                        modifier = Modifier.padding(top = LenslySpacing.sm),
                    )
                }
                explorerNotice?.let {
                    Spacer(Modifier.height(LenslySpacing.md))
                    InlineBanner(message = it, tone = BannerTone.Error)
                }
            }
        }
    }
}

/**
 * The success mark of a sent transaction: a check in a circle that springs in once (section 3.4). Reduced motion shows it at once, at full size.
 */
@Composable
private fun SentMark() {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(if (reduced) 1f else 0.6f) }
    LaunchedEffect(reduced) { if (!reduced) scale.animateTo(1f, LumenMotion.bouncy) }
    Box(
        modifier = Modifier
            .padding(bottom = LenslySpacing.sm)
            .size(56.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** The label of a field in the summary island, in the small label style. */
@Composable
private fun ReviewLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The rows of an EVM send: the sender, the recipient, the amount and the most the fee can be. */
fun evmSummary(send: PreparedSend): List<SummaryRow> {
    val token = send.token
    val chain = send.chain
    val network = if (chain.testnet) "${chain.name}, testnet" else chain.name
    val amount = if (token == null) {
        EvmFormat.amount(send.valueWei, chain.decimals, chain.symbol)
    } else {
        EvmFormat.amount(send.valueWei, token.decimals, token.symbol)
    }
    return listOf(
        SummaryRow("Network", network),
        SummaryRow("From", send.from),
        SummaryRow("To", send.to),
        SummaryRow("Amount", amount),
        SummaryRow("Fee, at most", EvmFormat.amount(send.maxFeeWei, chain.decimals, chain.symbol)),
    )
}

/** The rows of a Bitcoin send: the account, the recipient, the amount and the fee. */
fun btcSummary(plan: BtcSendPlan): List<SummaryRow> = listOf(
    SummaryRow("Network", if (plan.network == BtcNetwork.TESTNET4) "Bitcoin testnet4" else "Bitcoin mainnet"),
    SummaryRow("From", "${PairLabels.scriptType(plan.account.scriptType)}, ${plan.account.path.asString('\'')}"),
    SummaryRow("To", plan.recipient),
    SummaryRow("Amount", btcText(plan.amountSats)),
    SummaryRow("Fee", btcText(plan.feeSats)),
)

/**
 * The rows of a Solana send: the sender, the recipient, the amount and the fee. A token send names its amount in [tokenSymbol]; its fee is in SOL.
 */
fun solanaSummary(plan: SolanaSendPlan, tokenSymbol: String = "SOL"): List<SummaryRow> {
    val token = plan.token
    val amount = if (token == null) {
        "${SolanaSendInput.format(BigInteger.valueOf(plan.lamports))} SOL"
    } else {
        "${SolanaAssets.text(token.amount, token.decimals)} $tokenSymbol"
    }
    return listOf(
        SummaryRow("Network", if (plan.network.testnet) "Solana devnet" else "Solana mainnet-beta"),
        SummaryRow("From", plan.senderAddress),
        SummaryRow("To", plan.recipientAddress),
        SummaryRow("Amount", amount),
        SummaryRow("Fee", "${SolanaSendInput.format(BigInteger.valueOf(plan.feeLamports))} SOL"),
    )
}

/**
 * The rows of an Algorand send: the sender, the recipient, the amount and the fee, in ALGO. An asset send names its amount in [symbol] and [decimals].
 * An opt-in names the asset instead of a recipient, since it sends nothing.
 */
fun algorandSummary(plan: AlgorandSendPlan, symbol: String = "ALGO", decimals: Int = 6): List<SummaryRow> {
    val network = SummaryRow("Network", if (plan.network.testnet) "Algorand testnet" else "Algorand mainnet")
    val from = SummaryRow("From", plan.fromAddress)
    val fee = SummaryRow("Fee", AlgorandFormat.amount(plan.fee))
    if (plan is AsaSendPlan && plan.isOptIn) {
        return listOf(network, from, SummaryRow("Asset", symbol), SummaryRow("Action", "Opt in"), fee)
    }
    return listOf(network, from, SummaryRow("To", plan.toAddress), SummaryRow("Amount", AlgorandAssets.amountText(plan, symbol, decimals)), fee)
}
