package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.ui.settings.NoticeBlock
import app.lensly.ui.settings.ThirdPartyLicencesScreen
import app.lensly.ui.settings.readThirdPartyNotice

/** The third-party licences of AirVault, from the notice in :shared. The text is read off the main thread when the screen opens. */
@Composable
internal fun LicencesRoute(navController: NavHostController) {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<NoticeBlock>?>(null) }
    LaunchedEffect(Unit) {
        blocks = readThirdPartyNotice(context)
    }
    ThirdPartyLicencesScreen(blocks = blocks, onBack = { navController.popBackStack() })
}
