package app.lumio.ui.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.BtcSendPlan
import app.lumio.btc.btcText
import app.lumio.data.provider.algorand.AlgorandFormat
import app.lumio.evm.EvmFormat
import app.lumio.evm.PreparedSend
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.send.algorand.AlgorandSendPlan
import app.lumio.send.solana.SolanaSendInput
import app.lumio.send.solana.SolanaSendPlan
import app.lumio.ui.pair.PairLabels
import java.math.BigInteger

/** One line of what a signed transaction does, as the owner reads it. */
class SummaryRow(val label: String, val value: String)

/**
 * The last step of every send, on every chain: the signed transaction summarised, with one primary action. Nothing is sent until the owner taps
 * Submit. A refusal shows the network's words, with Try again, which sends the same signed bytes. Once the network has the transaction, the screen
 * shows its id, which copies on a tap, with the network's [status] when it reports one, and the explorer page when the chain has one.
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
    LenslyScreen(
        title = if (state is SubmitState.Sent) "Sent" else "Submit",
        onBack = if (submitting) null else onBack,
        bottomAction = {
            when (val current = state) {
                SubmitState.Idle -> SubmitPill("Submit", onSubmit)
                SubmitState.Submitting -> SubmitPill("Submitting", onClick = {}, enabled = false)
                is SubmitState.Failed -> if (current.retryable) SubmitPill("Try again", onSubmit) else SubmitPill("Start a new send", onStartOver)
                is SubmitState.Sent -> SubmitPill("Done", onDone)
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
            is SubmitState.Sent -> Unit
        }
        Spacer(Modifier.height(LenslySpacing.md))
        rows.forEach { ReviewField(it.label, it.value) }
        when (val current = state) {
            SubmitState.Idle, SubmitState.Submitting -> Unit
            is SubmitState.Failed -> {
                Spacer(Modifier.height(LenslySpacing.md))
                InlineBanner(message = current.message, tone = BannerTone.Error)
            }
            is SubmitState.Sent -> {
                val id = current.id
                Spacer(Modifier.height(LenslySpacing.md))
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
                    Text("TRANSACTION ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AddressText(address = id, shown = id, icon = false)
                }
                status?.let {
                    Spacer(Modifier.height(LenslySpacing.sm))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (explorerUrl(id) != null) {
                    Spacer(Modifier.height(LenslySpacing.md))
                    SettingsRow(title = "View on explorer", onClick = { onOpenExplorer(id) })
                }
                explorerNotice?.let {
                    Spacer(Modifier.height(LenslySpacing.md))
                    InlineBanner(message = it, tone = BannerTone.Error)
                }
            }
        }
    }
}

@Composable
private fun SubmitPill(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    WalletPill(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        style = WalletPillStyle.Primary,
        enabled = enabled,
    )
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

/** The rows of a Solana send: the sender, the recipient, the amount and the fee, in SOL. */
fun solanaSummary(plan: SolanaSendPlan): List<SummaryRow> = listOf(
    SummaryRow("Network", if (plan.network.testnet) "Solana devnet" else "Solana mainnet-beta"),
    SummaryRow("From", plan.senderAddress),
    SummaryRow("To", plan.recipientAddress),
    SummaryRow("Amount", "${SolanaSendInput.format(BigInteger.valueOf(plan.lamports))} SOL"),
    SummaryRow("Fee", "${SolanaSendInput.format(BigInteger.valueOf(plan.feeLamports))} SOL"),
)

/** The rows of an Algorand send: the sender, the recipient, the amount and the fee, in ALGO. */
fun algorandSummary(plan: AlgorandSendPlan): List<SummaryRow> = listOf(
    SummaryRow("Network", if (plan.network.testnet) "Algorand testnet" else "Algorand mainnet"),
    SummaryRow("From", plan.fromAddress),
    SummaryRow("To", plan.toAddress),
    SummaryRow("Amount", AlgorandFormat.amount(plan.amount)),
    SummaryRow("Fee", AlgorandFormat.amount(plan.fee)),
)
