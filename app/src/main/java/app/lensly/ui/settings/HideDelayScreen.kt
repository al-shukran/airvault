package app.lensly.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import app.lensly.app.HideDelay
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.NumberFieldSetting
import app.lensly.ui.components.SettingsItem

/**
 * The hide delay of the dice and coin tiles, shortest and longest, in milliseconds, as two number fields. Each applies as it is typed,
 * while the range stays one Settings accepts: the shortest is at most the longest, and the longest is at most [HideDelay.LIMIT_MS].
 * [shortest] and [longest] are the saved delays, null while they are read, and the fields are off until then.
 */
@Composable
fun HideDelayScreen(
    shortest: Int?,
    longest: Int?,
    onShortest: (Int) -> Unit,
    onLongest: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Hide Delay", modifier = modifier, onBack = onBack) {
        Text(
            text = "The dice and coin tiles stay hidden for a random time after a tap, drawn evenly from the shortest to the longest. " +
                "Each is from 0 to ${HideDelay.LIMIT_MS} ms.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Each field is keyed on the other one's saved value, so a number typed earlier is dropped when the other delay moves the range.
        SettingsItem {
            key(longest) {
                NumberFieldSetting(
                    title = "Shortest Delay",
                    value = shortest,
                    range = 0..(longest ?: HideDelay.LIMIT_MS.toInt()),
                    suffix = "ms",
                    onValid = onShortest,
                )
            }
        }
        SettingsItem {
            key(shortest) {
                NumberFieldSetting(
                    title = "Longest Delay",
                    value = longest,
                    range = (shortest ?: 0)..HideDelay.LIMIT_MS.toInt(),
                    suffix = "ms",
                    onValid = onLongest,
                )
            }
        }
    }
}
