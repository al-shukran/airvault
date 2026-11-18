package app.lumio.ui.send

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.ui.kit.StepThread

/** The nodes of the send's step thread (section 2.1), in the order the owner goes through them. */
internal const val SEND_STEP_DETAILS = 0
internal const val SEND_STEP_REVIEW = 1
internal const val SEND_STEP_SIGN = 2
internal const val SEND_STEP_SEND = 3

/**
 * The frame of a send step on the Lumen kit: the step thread under the top bar with [step] filled in, then the [content], and the one primary
 * action pinned at the bottom in [bottomAction]. [title] is sentence case, as the top bar shows it.
 */
@Composable
internal fun SendStepScaffold(
    step: Int,
    title: String,
    onBack: (() -> Unit)?,
    bottomAction: @Composable ColumnScope.() -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    LenslyScreen(title = title, onBack = onBack, scrollable = scrollable, bottomAction = bottomAction) {
        StepThread(current = step, modifier = Modifier.padding(bottom = LenslySpacing.md))
        content()
    }
}

/** A section header of a send step: sentence case, never uppercase (control rule 8). */
@Composable
internal fun StepLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = LenslySpacing.md, bottom = LenslySpacing.xxs),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
