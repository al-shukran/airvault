package app.lumio.ui.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/** The tag of the pager, which the tests swipe. */
const val FIRST_RUN_INTRO_TAG = "first-run-intro"

/** The statements I1 to I4 in the owner's order: Welcome, Keys, Never Share and Provided as Is. */
private const val INTRO_PAGES = 4

/**
 * The statements I1 to I4 as one pager. The owner swipes between them, or takes the capsule of a page. Back goes to the page before, and from the
 * first page it leaves the First Run through [onLeave]. The capsule of the last page goes on to the Important Notice through [onNotice]. The pager
 * opens on the first page, drawn on the first frame. Under reduced motion a change of page is a cut, not a slide.
 */
@Composable
fun FirstRunIntro(onLeave: () -> Unit, onNotice: () -> Unit) {
    val pager = rememberPagerState(pageCount = { INTRO_PAGES })
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    fun goTo(page: Int) {
        scope.launch {
            if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page)
        }
    }
    BackHandler {
        if (pager.currentPage > 0) goTo(pager.currentPage - 1) else onLeave()
    }
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxSize().testTag(FIRST_RUN_INTRO_TAG),
    ) { page ->
        when (page) {
            0 -> WelcomeScreen(onNext = { goTo(1) })
            1 -> KeysScreen(onNext = { goTo(2) })
            2 -> NeverShareScreen(onNext = { goTo(3) })
            else -> ProvidedAsIsScreen(onNext = onNotice)
        }
    }
}
