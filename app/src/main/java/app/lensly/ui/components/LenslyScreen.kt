package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing

/** Height of the top bar. The back button is 48 dp and sits centred in it. */
private val TOP_BAR_HEIGHT = 56.dp

/**
 * Standard screen frame: a top bar with an optional back button and a [title] in the dot-matrix headline face, then the
 * content with the page gutter. The content scrolls when it is taller than the space left, unless [scrollable] is false.
 * [bottomAction] is pinned under the content. Safe insets apply on every edge.
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
            .background(LenslyBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LenslyTopBar(title = title, onBack = onBack)
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

@Composable
private fun LenslyTopBar(title: String, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = LenslyOnSurface,
                )
            }
        } else {
            // Keeps the title on the page gutter when there is no back button.
            Spacer(Modifier.width(LenslySpacing.md - LenslySpacing.xxs))
        }
        Text(
            text = title.uppercase(),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = LenslyOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
