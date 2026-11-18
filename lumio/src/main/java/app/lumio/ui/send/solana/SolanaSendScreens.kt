package app.lumio.ui.send.solana

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalMonoFamily
import app.lumio.data.net.NetworkId
import app.lumio.send.solana.SOLANA_FEE_LAMPORTS
import app.lumio.send.solana.SOLANA_RENT_RESERVE_LAMPORTS
import app.lumio.send.solana.SolanaOutcome
import app.lumio.send.solana.SolanaSendInput
import app.lumio.send.solana.SolanaSendPlan
import java.math.BigInteger

/**
 * The send form: the account the send comes from, which is fixed, the recipient, and the amount in SOL with a Max text action. [busy] holds the
 * form while the network is read. [notice] is why the last attempt did not go through.
 */
@Composable
internal fun SolanaSendFormScreen(
    network: NetworkId,
    sender: String,
    balance: BigInteger?,
    recipient: String,
    onRecipient: (String) -> Unit,
    onScan: () -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    onMax: () -> Unit,
    busy: Boolean,
    notice: String?,
    onReview: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "Send SOL",
        onBack = onBack,
        bottomAction = {
            PillButton(text = "Review", onClick = onReview, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        },
    ) {
        NetworkName(network)
        Spacer(Modifier.height(LenslySpacing.sm))
        AddressText(address = sender, shown = sender, icon = false)
        balance?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Balance ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${SolanaSendInput.format(it)} SOL",
                    style = MonoBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        RecipientField(value = recipient, onValueChange = onRecipient, enabled = !busy, onScan = onScan)
        Spacer(Modifier.height(LenslySpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amount,
                onValueChange = onAmount,
                label = { Text("Amount") },
                suffix = { Text("SOL") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onMax, enabled = !busy && balance != null) {
                Text("Max")
            }
        }
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

/**
 * The review of a send before Lensly is asked to sign it: a sentence that says what leaves, then the rows the owner checks. Lensly keeps the rent
 * reserve in the sender, so the account stays open.
 */
@Composable
internal fun SolanaSendReviewScreen(plan: SolanaSendPlan, notice: String?, onConfirm: () -> Unit, onBack: () -> Unit) {
    val amount = BigInteger.valueOf(plan.lamports)
    val fee = BigInteger.valueOf(plan.feeLamports)
    LenslyScreen(
        title = "Review",
        onBack = onBack,
        bottomAction = {
            PillButton(text = "Show to Lensly", onClick = onConfirm, modifier = Modifier.fillMaxWidth())
        },
    ) {
        NetworkName(plan.network)
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            "Send ${SolanaSendInput.format(amount)} SOL to ${shortAddress(plan.recipientAddress)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        ReviewAddress(label = "From", address = plan.senderAddress)
        ReviewAddress(label = "To", address = plan.recipientAddress)
        ReviewValue(label = "Amount", value = "${SolanaSendInput.format(amount)} SOL", mono = true)
        ReviewValue(label = "Network fee", value = "${SolanaSendInput.format(fee)} SOL, ${plan.feeLamports} lamports", mono = true)
        ReviewValue(label = "Total", value = "${SolanaSendInput.format(amount + fee)} SOL", mono = true)
        ReviewValue(label = "Network", value = networkName(plan.network), mono = false)
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
    LenslyScreen(
        title = "Offline signing",
        onBack = onBack,
        bottomAction = {
            PillButton(text = "Show to Lensly", onClick = onConfirm, modifier = Modifier.fillMaxWidth())
        },
    ) {
        NetworkName(network)
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            "Create the nonce account for this account. You do this once.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        ReviewAddress(label = "Account", address = sender)
        ReviewAddress(label = "Nonce account", address = nonceAccount)
        ReviewValue(
            label = "Rent, paid into the nonce account",
            value = "${SolanaSendInput.format(BigInteger.valueOf(rentLamports))} SOL",
            mono = true,
        )
        ReviewValue(
            label = "Network fee",
            value = "${SolanaSendInput.format(BigInteger.valueOf(SOLANA_FEE_LAMPORTS))} SOL, $SOLANA_FEE_LAMPORTS lamports",
            mono = true,
        )
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
 * The request as an animated code for Lensly to scan. [notice] is why the last signature did not send. A [setup] request says so in plain words and
 * gives the time the setup's blockhash allows.
 */
@Composable
internal fun SolanaSignRequestScreen(parts: List<String>, notice: String?, onScan: () -> Unit, onBack: () -> Unit, setup: Boolean = false) {
    LenslyScreen(
        title = "Sign in Lensly",
        onBack = onBack,
        scrollable = false,
        bottomAction = {
            PillButton(text = "Scan signature", onClick = onScan, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Text(
            if (setup) SETUP_SIGN_TEXT else "In Lensly, scan this code, check the review and approve. Then scan the signature that Lensly shows.",
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

/** Why [text] is not a recipient the Solana send can pay, or null when it is one. The words are the ones the send refuses with. */
internal fun recipientError(text: String): String? = runCatching { SolanaSendInput.recipientAddress(text) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a Solana address." }

/** The network's name, with a Testnet chip when it is the devnet. */
@Composable
private fun NetworkName(network: NetworkId) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(networkName(network), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (network.testnet) {
            Spacer(Modifier.width(LenslySpacing.xs))
            PillChip(text = "Testnet")
        }
    }
}

/** A label in sans, with its value under it: an amount in mono, or a name in sans. */
@Composable
private fun ReviewValue(label: String, value: String, mono: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) MonoBody else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReviewAddress(label: String, address: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AddressText(address = address, shown = address, icon = false)
    }
}

/** The recipient field: the address in mono, a scan action beside it, and the reason an address is refused under it. */
@Composable
private fun RecipientField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onScan: () -> Unit) {
    val error = if (value.isEmpty()) null else recipientError(value)
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(error) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Recipient address") },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = supporting,
        textStyle = MonoBody.copy(color = MaterialTheme.colorScheme.onSurface),
        trailingIcon = {
            IconButton(onClick = onScan, enabled = enabled) {
                Icon(LenslyIcons.QrScan, contentDescription = "Scan a QR code")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Body size in the mono face, for amounts, addresses and hashes. */
private val MonoBody: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalMonoFamily.current)

private fun networkName(network: NetworkId): String = if (network.testnet) "Devnet" else "Mainnet-beta"

internal fun solanaOutcomeText(network: NetworkId, outcome: SolanaOutcome?): String = when (outcome) {
    null -> "Checking the network."
    SolanaOutcome.CONFIRMED -> "Confirmed on ${networkName(network)}."
    SolanaOutcome.FAILED -> "The network ran this transaction, and it failed."
    SolanaOutcome.PENDING -> "Not confirmed yet. Check the explorer in a minute, with this signature."
}

/** The first and last characters of an address, with the middle left out. The review shows the whole address in its rows. */
private fun shortAddress(address: String): String = "${address.take(6)}…${address.takeLast(4)}"
