package app.lensly.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.SigningSourcePolicy
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.RadioRow
import app.lensly.ui.components.SettingsIsland

/**
 * The Bitcoin policies, in two islands of radio rows: how input amounts are verified, and which online side may ask for a Bitcoin
 * signature. Each option has its own one-line description. A tap applies the choice at once, so there is no Save. [ready] is false
 * until the saved choices have been read, and the rows are off until then.
 */
@Composable
fun BitcoinPolicyScreen(
    inputPolicy: InputVerificationPolicy,
    signingSource: SigningSourcePolicy,
    ready: Boolean,
    onInputPolicy: (InputVerificationPolicy) -> Unit,
    onSigningSource: (SigningSourcePolicy) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Bitcoin Policy", modifier = modifier, onBack = onBack) {
        SettingsIsland("Input Amounts") {
            InputVerificationPolicy.entries.forEachIndexed { index, policy ->
                if (index > 0) IslandDivider()
                RadioRow(
                    title = inputPolicyLabel(policy),
                    description = inputPolicyDescription(policy),
                    selected = policy == inputPolicy,
                    onSelect = { onInputPolicy(policy) },
                    enabled = ready,
                    divided = false,
                )
            }
        }
        SettingsIsland("Signing Source") {
            SigningSourcePolicy.entries.forEachIndexed { index, source ->
                if (index > 0) IslandDivider()
                RadioRow(
                    title = signingSourceLabel(source),
                    description = signingSourceDescription(source),
                    selected = source == signingSource,
                    onSelect = { onSigningSource(source) },
                    enabled = ready,
                    divided = false,
                )
            }
        }
    }
}

/** The name of an input verification policy, as its radio row shows it. */
internal fun inputPolicyLabel(policy: InputVerificationPolicy): String = when (policy) {
    InputVerificationPolicy.STRICT -> "Strict"
    InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING -> "Segwit Unverified"
    InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED -> "Legacy Strict"
}

/** What an input verification policy means, in one line under its name. */
internal fun inputPolicyDescription(policy: InputVerificationPolicy): String = when (policy) {
    InputVerificationPolicy.STRICT -> "Each amount is verified from its input."
    InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING -> "Segwit amounts are not verified. You confirm them."
    InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED -> "Legacy verified; segwit needs no confirmation."
}

/** The name of a signing source policy, as its radio row shows it. */
internal fun signingSourceLabel(source: SigningSourcePolicy): String = when (source) {
    SigningSourcePolicy.ANY -> "Any Online Wallet"
    SigningSourcePolicy.COMPANION_ONLY -> "Lumio Only"
}

/** What a signing source policy means, in one line under its name. */
internal fun signingSourceDescription(source: SigningSourcePolicy): String = when (source) {
    SigningSourcePolicy.ANY -> "Any online wallet whose code the vault reads."
    SigningSourcePolicy.COMPANION_ONLY -> "Only requests marked by Lumio are signed; the mark is not checked."
}
