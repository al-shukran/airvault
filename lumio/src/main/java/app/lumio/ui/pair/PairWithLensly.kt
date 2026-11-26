package app.lumio.ui.pair

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.firstrun.FirstRunFooter
import app.lumio.ui.firstrun.FirstRunPage
import app.lumio.ui.firstrun.FirstRunStep
import app.lumio.ui.firstrun.FirstRunTextAction
import app.lumio.ui.firstrun.NumberedStep
import app.lumio.ui.firstrun.PageSmall
import app.lumio.ui.firstrun.PageSpacing
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirPageTopBar
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.NoteCard
import app.lumio.ui.scan.CameraDeniedNote
import app.lumio.ui.scan.SegmentedViewfinder

/** The Camera Denied words on Pair, which reads Lensly's code. */
const val PAIR_CAMERA_DENIED_TEXT = "Lumio needs the camera to read Lensly's code. Nothing is recorded."

/** The two steps of the Pair with Lensly page, in the owner's words. */
internal val PAIR_STEPS = listOf("On Lensly, open Pair and choose the wallet to share.", "Hold Lensly's code inside the frame.")

/** The line under the camera that says what Lumio reads from the code. */
const val PAIR_PUBLIC_ONLY = "Lumio reads public addresses only. Your keys stay on Lensly."

/** What the page says when the owner asks where Pair is on Lensly. */
private const val PAIR_HELP = "On Lensly, open Pair and choose the wallet to share. Hold Lensly's code inside the frame."

/** The height of the frame's camera: the page's scan frame is the same size when the camera is refused. */
private val CAMERA_FRAME_HEIGHT = 300.dp

/**
 * What surrounds the Pair with Lensly page. [Onboarding] is step 10 of the first run, which the mock draws with the chapter bar, the eyebrow and the
 * title and no top bar. A [Page] is the same page pushed from Wallets, Settings or Re-Pair: the Lumio plain-push top bar carries the [Page.title]
 * ("Pair with Lensly" or "Re-Pair with Lensly"), and the content under it is the page's.
 */
sealed interface PairChrome {
    data object Onboarding : PairChrome

    data class Page(val title: String, val onBack: () -> Unit, val onLock: () -> Unit) : PairChrome
}

/**
 * The Pair with Lensly page (mock step 10, and K6 when the camera is refused): the two steps, the camera, what the scan says, and what Lumio reads,
 * with Where Is Pair on Lensly? at the foot. The first run and the manual pairing both show this page, so they cannot drift apart: only the [chrome]
 * around it differs. [progress] is how much of an animated code is in, and [status] says what the scan is doing. The camera is [viewport], or the
 * Camera Denied note when it is [denied], and then Open Settings joins the foot.
 */
@Composable
fun PairWithLensly(
    chrome: PairChrome,
    progress: Float?,
    status: String,
    complete: Boolean,
    denied: Boolean,
    onOpenSettings: () -> Unit,
    viewport: @Composable (Modifier) -> Unit,
) {
    var help by remember { mutableStateOf(false) }
    val body: @Composable () -> Unit = {
        PAIR_STEPS.forEachIndexed { index, step -> NumberedStep(number = index + 1, text = step) }
        Spacer(Modifier.height(5.dp))
        if (denied) {
            CameraDeniedNote(onOpenSettings = null, text = PAIR_CAMERA_DENIED_TEXT, modifier = Modifier.fillMaxWidth().height(CAMERA_FRAME_HEIGHT))
        } else {
            SegmentedViewfinder(progress = progress, complete = complete, caption = status, viewport = viewport)
            Spacer(Modifier.height(12.dp))
            Text(text = PAIR_PUBLIC_ONLY, style = PageSmall, color = LocalClearAirPalette.current.ink2)
        }
        if (help) {
            Spacer(Modifier.height(ClearAirSpacing.md))
            NoteCard(glyph = ClearAirGlyph.INFO, text = PAIR_HELP, modifier = Modifier)
        }
    }
    val footer: @Composable () -> Unit = {
        if (denied) ClearAirPrimaryButton(text = "Open Settings", onClick = onOpenSettings)
        FirstRunTextAction(
            text = "Where Is Pair on Lensly?",
            onClick = { help = !help },
            modifier = if (denied) Modifier else Modifier.padding(bottom = 6.dp),
        )
    }
    when (chrome) {
        PairChrome.Onboarding -> FirstRunPage(
            step = FirstRunStep.Pair,
            eyebrow = "Pair · 2 of 2",
            title = "Pair with Lensly",
            footer = footer,
            spacing = PageSpacing(eyebrowTop = 24.9.dp, titleTop = 10.2.dp, bodyTop = 10.5.dp),
            content = body,
        )
        is PairChrome.Page -> Column(modifier = Modifier.fillMaxSize()) {
            ClearAirPageTopBar(title = chrome.title, backLabel = null, onBack = chrome.onBack, onLock = chrome.onLock)
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ClearAirSpacing.gutter)) { body() }
            FirstRunFooter(footer)
        }
    }
}
