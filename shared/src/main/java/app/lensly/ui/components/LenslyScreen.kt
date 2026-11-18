package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.LenslySpacing

/**
 * Standard screen frame: the [DetailTopBar] with Back and the [title], then the content with the page gutter. The content scrolls when it
 * is taller than the space left, unless [scrollable] is false. [bottomAction] is pinned under the content. Safe insets apply on every edge.
 */
@Composable
fun LenslyScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    bottomAction: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        DetailTopBar(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = LenslySpacing.md),
            content = content,
        )
        if (bottomAction != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.md),
                content = bottomAction,
            )
        }
    }
}
