package app.lensly.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing

/** The notice the build packs into the app's assets. Its source is licenses/THIRD-PARTY-NOTICES.txt in the repository. */
internal const val THIRD_PARTY_NOTICE_PATH = "THIRD-PARTY-NOTICES.txt"

/** One block of the notice: a heading, or a paragraph of text. */
data class NoticeBlock(val text: String, val heading: Boolean)

/**
 * Splits [notice] into blocks. Blocks are separated by a blank line, and the lines inside a block are joined with a space, so
 * each block wraps to the width of the screen. A block written as "==== Title ====" is a heading, and so is a short block that
 * ends with a colon.
 */
internal fun noticeBlocks(notice: String): List<NoticeBlock> = notice
    .split(Regex("\\n\\s*\\n"))
    .map { block -> block.lines().joinToString(" ") { it.trim() }.trim() }
    .filter { it.isNotEmpty() }
    .map { block ->
        when {
            block.startsWith("==== ") && block.endsWith(" ====") -> NoticeBlock(block.removeSurrounding("==== ", " ===="), true)
            block.endsWith(":") && block.length <= MAX_HEADING_LENGTH -> NoticeBlock(block, true)
            else -> NoticeBlock(block, false)
        }
    }

private const val MAX_HEADING_LENGTH = 120

/**
 * The third-party licences the app ships: every library and font in the package, with the text its licence asks to keep.
 * [blocks] is null while the notice is read, and the list scrolls once it is there.
 */
@Composable
fun ThirdPartyLicencesScreen(
    blocks: List<NoticeBlock>?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Third-party licences", modifier = modifier, onBack = onBack, scrollable = false) {
        if (blocks == null) {
            DotProgress(description = "Reading the third-party licences")
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(blocks) { block -> NoticeText(block) }
                item { Spacer(Modifier.height(LenslySpacing.xl)) }
            }
        }
    }
}

@Composable
private fun NoticeText(block: NoticeBlock) {
    if (block.heading) {
        Text(
            text = block.text,
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.lg).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = LenslyOnSurface,
        )
    } else {
        Text(
            text = block.text,
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.sm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
