package app.lensly.ui.sign

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslySpacing

/**
 * Whether the owner has read a review, which must hold before its sign control may run. [listReached] says that the list is scrolled to
 * its end, or that it fits on screen. A review with no warning is read by that alone. A review with a warning is read only once a details
 * section has also been opened, because the warning is explained there, and the banner on its own is not a read.
 */
internal fun isReviewRead(hasWarnings: Boolean, detailsOpened: Boolean, listReached: Boolean): Boolean =
    listReached && (!hasWarnings || detailsOpened)

/**
 * What the owner has read of one review: how far its list is scrolled and measured, whether a details section has opened, and whether the
 * review counts as read. A new review starts unread. Once read, a review stays read, so folding a section back does not undo it.
 */
@Stable
internal class ReviewRead(val scroll: ScrollState) {
    /** The visible height of the list, and the height of its content, in pixels, as layout measured them. */
    var viewportPx by mutableIntStateOf(0)
    var contentPx by mutableIntStateOf(0)

    /** True once a details section has been opened. */
    var detailsOpened by mutableStateOf(false)
        private set

    /** True once the review counts as read. */
    var read by mutableStateOf(false)
        private set

    /** True when the list is scrolled to its end. */
    val atEnd by derivedStateOf { scroll.maxValue > 0 && scroll.value >= scroll.maxValue }

    /** True when the list fits on screen, so there is nothing to scroll to. */
    val fits by derivedStateOf { viewportPx > 0 && contentPx in 1..viewportPx }

    /** Runs when a details section opens. */
    fun openDetails() {
        detailsOpened = true
    }

    /** Marks the review read once [isReviewRead] holds for it. */
    fun settle(hasWarnings: Boolean) {
        if (isReviewRead(hasWarnings, detailsOpened, atEnd || fits)) read = true
    }
}

/**
 * The read state of the review on screen. It is new for each review, and it is settled again whenever the list is scrolled or measured,
 * or a details section opens.
 */
@Composable
internal fun rememberReviewRead(review: Any, hasWarnings: Boolean): ReviewRead {
    val scroll = rememberScrollState()
    val read = remember(review) { ReviewRead(scroll) }
    val reached = read.atEnd || read.fits
    LaunchedEffect(reached, read.detailsOpened, hasWarnings) { read.settle(hasWarnings) }
    return read
}

/**
 * The line above the sign control while the review is unread: the details first when the review has a warning, then the end of the list.
 * Null once the review is read.
 */
internal fun readHint(read: ReviewRead, hasWarnings: Boolean): String? = when {
    read.read -> null
    hasWarnings && !read.detailsOpened -> "Open details to continue"
    else -> "Scroll to the end to review everything"
}

/**
 * The list of a review. It fills the space above the bottom actions and scrolls, and a fade runs over its cut-off edge while more lies
 * below. The list is measured, so [ReviewRead] knows whether it fits on screen.
 */
@Composable
internal fun ColumnScope.ReviewBody(read: ReviewRead, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { read.viewportPx = it.height }
                .verticalScroll(read.scroll),
        ) {
            Column(modifier = Modifier.fillMaxWidth().onSizeChanged { read.contentPx = it.height }, content = content)
        }
        // A fade over the cut-off edge while more facts lie below, so the list reads as scrolling, not clipped.
        if (!read.atEnd) {
            ScrollFade(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The fade at the bottom of the list. It runs from transparent to the page colour, so it sits over the content. */
@Composable
private fun ScrollFade(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LenslySpacing.xl)
            .background(Brush.verticalGradient(listOf(Color.Transparent, LenslyBlack))),
    )
}
