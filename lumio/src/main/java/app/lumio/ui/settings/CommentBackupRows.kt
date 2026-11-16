package app.lumio.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslySpacing

/**
 * The two rows of the comment backup on the Transactions page: export the comments to a file, and import them from one. [notice] is the result of the
 * last action, and it shows under the rows.
 */
@Composable
fun CommentBackupRows(onExport: () -> Unit, onImport: () -> Unit, notice: String?) {
    SettingsRow(
        title = "Export comments",
        description = "Saves every transaction comment to a file. Only this vault can open it.",
        onClick = onExport,
    )
    SettingsRow(
        title = "Import comments",
        description = "Restores the comments of a file that this vault exported. A newer comment here is kept.",
        onClick = onImport,
    )
    notice?.let {
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(text = it, style = MaterialTheme.typography.bodySmall)
    }
}
