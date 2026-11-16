package app.lensly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import app.lensly.app.WalletApp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.AppTitle
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing

/** How many accounts an App holds, in words a reader takes in at a glance: "1 account", "3 accounts". */
internal fun accountCountLabel(count: Int): String = if (count == 1) "1 account" else "$count accounts"

/** The logo of an App: the Bitcoin logo, or the Ethereum logo that every EVM address shows. It is decorative beside the App's name. */
@Composable
internal fun AppIcon(app: WalletApp, size: Dp, modifier: Modifier = Modifier) {
    when (app) {
        WalletApp.BITCOIN -> BitcoinIcon(size = size, modifier = modifier)
        WalletApp.ETHEREUM -> AddressIcon(size = size, modifier = modifier)
        WalletApp.SOLANA -> SolanaIcon(size = size, modifier = modifier)
        WalletApp.ALGORAND -> AlgorandIcon(size = size, modifier = modifier)
    }
}

/** The Solana mark beside a Solana account: three slanted bars. It is decorative: the text beside it names the account. */
@Composable
internal fun SolanaIcon(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val width = this.size.width
        val height = this.size.height
        val barHeight = height * 0.16f
        val slant = height * 0.14f
        repeat(3) { bar ->
            val top = height * (0.2f + bar * 0.26f)
            val path = Path().apply {
                moveTo(slant, top)
                lineTo(width, top)
                lineTo(width - slant, top + barHeight)
                lineTo(0f, top + barHeight)
                close()
            }
            drawPath(path, color = LenslyOnSurface)
        }
    }
}

/** The Algorand mark beside an Algorand account: a triangle with a bar across it. It is decorative: the text beside it names the account. */
@Composable
internal fun AlgorandIcon(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val width = this.size.width
        val height = this.size.height
        val stroke = height * 0.12f
        val triangle = Path().apply {
            moveTo(width / 2f, height * 0.08f)
            lineTo(width * 0.94f, height * 0.92f)
            lineTo(width * 0.06f, height * 0.92f)
            close()
        }
        drawPath(triangle, color = LenslyOnSurface, style = Stroke(width = stroke))
        drawLine(
            color = LenslyOnSurface,
            start = Offset(width * 0.3f, height * 0.62f),
            end = Offset(width * 0.7f, height * 0.62f),
            strokeWidth = stroke,
        )
    }
}

/**
 * The name of an App in the dot-matrix face, with its logo, under the name of the wallet it belongs to, so the reader knows whose
 * accounts the screen shows.
 */
@Composable
internal fun AppHeader(app: WalletApp, walletName: String) {
    AppTitle(logo = { size -> AppIcon(app = app, size = size) }, name = app.label, walletName = walletName)
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
