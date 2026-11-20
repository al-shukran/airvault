package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.text
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/** The most height a block of rows takes. Past it, the block scrolls on its own, so every row stays reachable. */
private val BLOCK_MAX_HEIGHT = 320.dp

/**
 * Shown when the vault refuses to sign a request, for example on a chain mismatch or a malformed payload. [reason] says
 * why, in plain words. The red REFUSED headline and the error banner make the outcome clear at a glance, and BACK TO
 * WALLET leaves the flow.
 *
 * A refusal that passes [rows] shows all of them, in order, so nothing is cut short: the whole call of a contract call Lensly
 * cannot decode. [networks] give each address row the logo of the network it is on.
 */
@Composable
fun SignRefusedScreen(
    reason: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    rows: List<ReviewRow> = emptyList(),
    networks: List<NetworkEntry> = emptyList(),
) {
    LenslyScreen(
        title = "Sign Request",
        modifier = modifier,
        bottomAction = {
            PillButton(
                text = "Back to Wallet",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "Refused",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        InlineBanner(message = reason, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.md))
        if (rows.isNotEmpty()) {
            Text(
                text = "Lensly cannot decode this call, so it will not sign it. The details below are the whole call.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(LenslySpacing.xs))
            RefusedRows(rows, networks)
            Spacer(Modifier.height(LenslySpacing.md))
        }
        Text(
            text = "Check the request on your online wallet, then start SIGN again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The rows of a refusal, in order. A row with a value is a fact: its label over its value. A row with no value heads the rows indented
 * under it, which form one block, drawn in a box that scrolls within [BLOCK_MAX_HEIGHT].
 */
@Composable
private fun RefusedRows(rows: List<ReviewRow>, networks: List<NetworkEntry>) {
    var next = 0
    while (next < rows.size) {
        val row = rows[next++]
        if (row.value != null) {
            FactRow(label = row.label) { RowValue(row, networks) }
            continue
        }
        val start = next
        while (next < rows.size && rows[next].depth > 0) next++
        val block = rows.subList(start, next)
        FactRow(label = row.label) { RefusedBlock(block, networks) }
    }
}

/** The rows of one block: each one's label above its value. The block scrolls within its own height. */
@Composable
private fun RefusedBlock(rows: List<ReviewRow>, networks: List<NetworkEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = BLOCK_MAX_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(LenslySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        rows.forEach { row ->
            MutedText(row.label)
            RowValue(row, networks)
        }
    }
}

/**
 * The value of a row, then its notes under it. An address is shown in full, with the logo of the network it is on, text in sans unless it
 * is mono, and any other value in mono.
 */
@Composable
private fun RowValue(row: ReviewRow, networks: List<NetworkEntry>) {
    when (val value = row.value) {
        is DisplayValue.Address -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChainNetworkLogo(
                chain = value.chain,
                networkName = networkOf(value.chain, networks)?.name,
                networks = networks,
                size = 16.dp,
            )
            Spacer(Modifier.width(LenslySpacing.xs))
            AddressText(address = value.address, icon = false, modifier = Modifier.weight(1f))
        }
        is DisplayValue.Text -> if (value.mono) {
            MonoText(text = value.text)
        } else {
            Text(
                text = value.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        else -> MonoText(text = value.text())
    }
    row.notes.forEach { NoteText(it) }
}
