package app.lumio.ui.send

import androidx.compose.runtime.Composable
import app.lumio.LumioApp
import app.lumio.ui.shell.offlineBand
import app.lumio.ui.shell.rememberOnline

/**
 * The offline band of a send page (K2): null while the device is online, otherwise the words that say which balances are shown and when they were
 * read. A send needs a connection, so the pages that start one disable their action while this is set.
 */
@Composable
internal fun rememberSendOffline(app: LumioApp): String? =
    if (rememberOnline()) null else offlineBand(app.lumioVault()?.wallets?.updatedAtMs)
