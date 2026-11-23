package app.lumio.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow

/**
 * The two rows of the comment backup on the Transactions page: export the comments to a file, and import them from one. [notice] is the result of the
 * last action, and it shows under the rows.
 */
@Composable
fun CommentBackupRows(onExport: () -> Unit, onImport: () -> Unit, notice: String?) {
    SettingsGroup(title = "Comments") {
        ClearAirRow(
            title = "Export Comments",
            description = "Saves every transaction comment to a file. Only this vault can open it.",
            onClick = onExport,
        )
        ClearAirRow(
            title = "Import Comments",
            description = "Restores the comments of a file that this vault exported. A newer comment here is kept.",
            onClick = onImport,
        )
        notice?.let {
            ClearAirNote(text = it, modifier = Modifier.padding(top = ClearAirSpacing.xs))
        }
    }
}
