package app.lensly.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import app.lensly.app.VisualIdentity

/**
 * The options of one account: its address goes out as a code when [onExport] is given, and its profile is edited. The address is public.
 * A Bitcoin account has no code of its own, so it passes neither [address] nor [onExport], and the sheet offers only the profile.
 */
@Composable
internal fun AccountOptionsSheet(
    name: String,
    identity: VisualIdentity,
    address: String?,
    onDismiss: () -> Unit,
    onExport: (() -> Unit)?,
    onEdit: () -> Unit,
) {
    OptionsSheet(onDismiss = onDismiss) {
        SheetHeader(name = name, identity = identity, address = address)
        if (onExport != null) SheetOption(icon = Icons.Filled.Share, label = "Export address", onClick = onExport)
        SheetOption(icon = Icons.Filled.Edit, label = "Edit profile", onClick = onEdit)
    }
}
