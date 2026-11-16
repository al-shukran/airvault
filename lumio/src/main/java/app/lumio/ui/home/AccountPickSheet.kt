package app.lumio.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.ui.components.ListRow
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lumio.home.AccountOption

/** The test tag of a picker row, by its place in the list. */
fun pickRowTag(index: Int): String = "pick-$index"

/** A sheet that lists the accounts a Send or a Receive can start from. A tap on a row calls [onPick] with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPickSheet(
    title: String,
    options: List<AccountOption>,
    emptyText: String,
    onPick: (AccountOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AccountPickContent(title = title, options = options, emptyText = emptyText, onPick = onPick)
    }
}

/** The content of [AccountPickSheet]: a heading, then one row for each option, or [emptyText] when there is none. */
@Composable
fun AccountPickContent(
    title: String,
    options: List<AccountOption>,
    emptyText: String,
    onPick: (AccountOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = WALLET_GUTTER, top = LenslySpacing.sm, end = WALLET_GUTTER, bottom = LenslySpacing.md),
    ) {
        WalletEyebrow(title)
        Spacer(Modifier.height(LenslySpacing.xs))
        if (options.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        options.forEachIndexed { index, option ->
            ListRow(onClick = { onPick(option) }, modifier = Modifier.testTag(pickRowTag(index))) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(option.title, style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
                    if (option.detail.isNotEmpty()) {
                        Text(
                            text = option.detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
