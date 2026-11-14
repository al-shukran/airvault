package app.lensly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.SigningSourcePolicy
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/**
 * The Bitcoin policies: how input amounts are verified, and which online side may ask for a Bitcoin signature. Each choice is one of
 * the policies the Bitcoin core defines, and the saved choice starts selected. SAVE stays off until the saved choices have been read.
 */
@Composable
fun BitcoinPolicyScreen(
    inputPolicy: InputVerificationPolicy,
    signingSource: SigningSourcePolicy,
    ready: Boolean,
    onInputPolicy: (InputVerificationPolicy) -> Unit,
    onSigningSource: (SigningSourcePolicy) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Bitcoin policy",
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            PillButton(text = "Save", onClick = onSave, modifier = Modifier.fillMaxWidth(), enabled = ready)
        },
    ) {
        Text(
            text = "Input amounts",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        InputVerificationPolicy.entries.forEach { policy ->
            PillButton(
                text = inputPolicyLabel(policy),
                onClick = { onInputPolicy(policy) },
                modifier = Modifier.fillMaxWidth(),
                accent = policy == inputPolicy,
                enabled = ready,
            )
            if (policy == inputPolicy) {
                Text(
                    text = inputPolicyDescription(policy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LenslySpacing.xs))
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "Signing source",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            SigningSourcePolicy.entries.forEach { source ->
                PillButton(
                    text = signingSourceLabel(source),
                    onClick = { onSigningSource(source) },
                    modifier = Modifier.fillMaxWidth(),
                    accent = source == signingSource,
                    enabled = ready,
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = signingSourceDescription(signingSource),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The name of an input verification policy on the choice pill. */
internal fun inputPolicyLabel(policy: InputVerificationPolicy): String = when (policy) {
    InputVerificationPolicy.STRICT -> "Strict"
    InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING -> "Segwit unverified"
    InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED -> "Legacy strict"
}

/** What an input verification policy means, shown under the chosen one. */
internal fun inputPolicyDescription(policy: InputVerificationPolicy): String = when (policy) {
    InputVerificationPolicy.STRICT ->
        "Every input carries its previous transaction, and every amount is verified from it."
    InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING ->
        "Segwit inputs may carry only a witness UTXO. Their amounts are not verified, so you confirm them before signing."
    InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED ->
        "Legacy inputs need their previous transaction. Segwit inputs may carry only a witness UTXO, with no extra confirmation."
}

/** The name of a signing source policy on the choice pill. */
internal fun signingSourceLabel(source: SigningSourcePolicy): String = when (source) {
    SigningSourcePolicy.ANY -> "Any online wallet"
    SigningSourcePolicy.COMPANION_ONLY -> "Lumio only"
}

/** What a signing source policy means, shown under the choices. */
internal fun signingSourceDescription(source: SigningSourcePolicy): String = when (source) {
    SigningSourcePolicy.ANY -> "Any online wallet that reads a code format the vault decodes may ask for a Bitcoin signature."
    SigningSourcePolicy.COMPANION_ONLY -> "Only a PSBT that the Lumio companion app marked may be signed. Other requests are refused."
}
