package app.lensly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.WalletApp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletEyebrow

/** How many accounts an App holds, in words a reader takes in at a glance: "1 account", "3 accounts". */
internal fun accountCountLabel(count: Int): String = if (count == 1) "1 account" else "$count accounts"

/** The logo of an App: the Bitcoin logo, or the Ethereum logo that every EVM address shows. It is decorative beside the App's name. */
@Composable
internal fun AppIcon(app: WalletApp, size: Dp, modifier: Modifier = Modifier) {
    when (app) {
        WalletApp.BITCOIN -> BitcoinIcon(size = size, modifier = modifier)
        WalletApp.ETHEREUM -> AddressIcon(size = size, modifier = modifier)
    }
}

/**
 * The name of an App in the dot-matrix face, with its logo, under the name of the wallet it belongs to, so the reader knows whose
 * accounts the screen shows.
 */
@Composable
internal fun AppHeader(app: WalletApp, walletName: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow(walletName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(LenslySpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app = app, size = 40.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            DotText(text = app.label.uppercase(), modifier = Modifier.weight(1f), fontSize = 32.sp)
        }
    }
}

/** The last row of an App's accounts: a dashed outline with a plus, which [label] names, such as "ADD ACCOUNT". */
@Composable
internal fun AddAccountRow(label: String, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    val radius = LenslyRadius.md
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = LenslyOutlineInteractive,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                )
            }
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = LenslyOnSurface,
        )
    }
}

/**
 * The options of one App: its generate and select actions, then pairing and signing, which act on the whole wallet. The App's logo
 * and name head the sheet. A null action is not offered: [onGenerate] is null for a Single Key, and [onSelect] is null where there
 * is no list of accounts to select from.
 */
@Composable
internal fun AppOptionsSheet(
    app: WalletApp,
    onDismiss: () -> Unit,
    onGenerate: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    onPair: () -> Unit,
    onScan: () -> Unit,
) {
    OptionsSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app = app, size = 40.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            Text(
                text = app.label.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = LenslyOnSurface,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = LenslySpacing.xs),
        )
        if (onGenerate != null) {
            SheetOption(icon = Icons.Filled.Add, label = "Generate accounts", onClick = onGenerate)
        }
        if (onSelect != null) {
            SheetOption(icon = Icons.Filled.Check, label = "Select accounts", onClick = onSelect)
        }
        SheetOption(icon = LenslyIcons.QrCode, label = "Pair this wallet", onClick = onPair)
        SheetOption(icon = LenslyIcons.QrScan, label = "Scan to sign", onClick = onScan)
    }
}
