package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.registry.ProxyBinding
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing

/**
 * A call Lensly cannot decode that the signed whitelist approves. It opens with a solid block and a strong warning, then shows the
 * approved meaning and every fact of the call, the calldata in full. A proxy reviewed under Warn and confirm adds its red warning
 * and the implementation it was approved with. Nothing here is cut short, so the scroll of the review covers all of it.
 */
@Composable
internal fun WhitelistedCallBlock(whitelisted: WhitelistedCall) {
    val call = whitelisted.call
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LenslyRadius.lg))
            .background(LenslyAccent)
            .padding(LenslySpacing.md),
    ) {
        Text(
            text = "WHITELISTED CONTRACT CALL",
            style = MaterialTheme.typography.headlineSmall,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "Lensly cannot decode this call itself. It is on your signed whitelist with the meaning below. " +
                "Sign only if this is what you intend.",
            style = MaterialTheme.typography.bodyMedium,
            color = LenslyOnSurface,
        )
    }
    call.proxy?.let { proxy ->
        Spacer(Modifier.height(LenslySpacing.sm))
        InlineBanner(
            title = "Upgradeable proxy",
            message = "The implementation could have been upgraded since it was approved, and Lensly cannot check it offline. " +
                "The implementation it was approved with is shown below.",
            tone = BannerTone.Error,
        )
    }
    Spacer(Modifier.height(LenslySpacing.sm))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LenslyRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = LenslySpacing.sm),
    ) {
        FactRow(label = "Approved meaning") {
            Text(
                text = whitelisted.meaning,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        FactRow(label = "Function signature") {
            MonoText(text = whitelisted.signature)
        }
        call.proxy?.let { ProxyFacts(it) }
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
        RawCalldata(words = call.words)
    }
}

/**
 * The calldata after the selector, folded away by default. Every 32-byte word is shown in full hex when opened, so the raw
 * bytes behind the parameters above stay available without crowding the review.
 */
@Composable
private fun RawCalldata(words: List<String>) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { open = !open })
            .padding(vertical = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MutedText("Raw calldata")
        Spacer(Modifier.weight(1f))
        MutedText(if (open) "HIDE" else "SHOW")
    }
    if (open) {
        if (words.isEmpty()) {
            MutedText("None")
        } else {
            words.forEachIndexed { index, word ->
                MutedText("WORD ${index + 1}")
                MonoText(text = "0x${groupHex(word)}")
                Spacer(Modifier.height(LenslySpacing.xs))
            }
        }
    }
}

/** The implementation a whitelisted proxy is bound to, in full: its address, its code hash, or both. */
@Composable
internal fun ProxyFacts(proxy: ProxyBinding) {
    proxy.implementationAddress?.let { address ->
        FactRow(label = "Approved implementation") {
            AddressText(address = address)
        }
    }
    proxy.implementationCodeHash?.let { hash ->
        FactRow(label = "Approved code hash") {
            MonoText(text = groupHex(hash.removePrefix("0x")))
        }
    }
}
