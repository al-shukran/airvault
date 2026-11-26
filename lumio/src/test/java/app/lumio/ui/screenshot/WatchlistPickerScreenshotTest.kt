package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of Add to Watchlist, light and dark: the list before a query and the results of "government". They are drawn at the mock's 411 × 891 dp
 * phone, under a 40 dp status bar, with the mock's rows and logos, so each golden lays over its frame of the approved mock. The rows are fixture data
 * only: the live list keeps only the coins Lumio can watch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchlistPickerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun pickerLight() = compose.clearAirSnapshot("watchlist_picker_light", PickerFixtures.PHONE) { PickerFixtures.Top() }

    @Test
    fun pickerDark() = compose.clearAirSnapshot("watchlist_picker_dark", PickerFixtures.PHONE) { PickerFixtures.Top() }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun searchLight() = compose.clearAirSnapshot("watchlist_picker_search_light", PickerFixtures.PHONE) { PickerFixtures.Search() }

    @Test
    fun searchDark() = compose.clearAirSnapshot("watchlist_picker_search_dark", PickerFixtures.PHONE) { PickerFixtures.Search() }
}
