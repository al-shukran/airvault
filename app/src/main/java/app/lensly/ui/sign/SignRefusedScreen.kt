package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.app.sign.UndecodedCall
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing

/** The most height the calldata block takes. Past it, the block scrolls on its own, so every word stays reachable. */
private val CALLDATA_MAX_HEIGHT = 320.dp

/**
 * Shown when the vault refuses to sign a request, for example on a chain mismatch or a malformed payload. [reason] says
 * why, in plain words. The red REFUSED headline and the error banner make the outcome clear at a glance, and BACK TO
 * WALLET leaves the flow.
 *
 * A contract call Lensly cannot decode also passes its [call], and the screen shows all of it: the contract, the network,
 * the native value, the selector and every 32-byte word of calldata.
 */
@Composable
fun SignRefusedScreen(
    reason: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    call: UndecodedCall? = null,
) {
    LenslyScreen(
        title = "Sign request",
        modifier = modifier,
        bottomAction = {
            PillButton(
                text = "Back to wallet",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "REFUSED",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displaySmall,
            color = LenslyError,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        InlineBanner(message = reason, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.md))
        if (call != null) {
            UndecodedCallDetails(call)
            Spacer(Modifier.height(LenslySpacing.md))
        }
        Text(
            text = "Check the request on your online wallet, then start SIGN again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What a call Lensly cannot decode would do, in full. It is the whole call, so nothing here is cut short. */
@Composable
private fun UndecodedCallDetails(call: UndecodedCall) {
    Text(
        text = "Lensly cannot decode this call, so it will not sign it. The details below are the whole call.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(LenslySpacing.xs))
    FactRow(label = "Contract") {
        AddressText(address = call.target)
    }
    FactRow(label = "Network") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            Text(
                text = call.networkName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PillChip(text = "Chain ${call.chainId}")
        }
    }
    FactRow(label = "Value") {
        MonoText(text = call.value)
    }
    FactRow(label = "Selector") {
        MonoText(text = "0x${call.selector}")
    }
    FactRow(label = "Calldata") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = CALLDATA_MAX_HEIGHT)
                .clip(RoundedCornerShape(LenslyRadius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .padding(LenslySpacing.sm),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            call.words.forEachIndexed { index, word ->
                MutedText("WORD ${index + 1}")
                MonoText(text = "0x${groupHex(word)}")
            }
        }
    }
}
