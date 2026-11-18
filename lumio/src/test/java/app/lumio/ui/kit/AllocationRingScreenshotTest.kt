package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumio.ui.screenshot.LUMIO_DARK_QUALIFIERS
import app.lumio.ui.screenshot.LUMIO_LIGHT_QUALIFIERS
import app.lumio.ui.screenshot.lumioSnapshot
import app.lumio.ui.theme.LumioGutter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the allocation ring (section 1.6): each state at the owner's phone size, in the dark and the light theme. They are recorded on the Linux
 * runner, and reduced motion is on, so each golden is a still frame. The selected golden shows the lift and the dim of a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AllocationRingScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loading() = compose.lumioSnapshot("allocation_loading") { Ring(AllocationState.Loading) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun loadingLight() = compose.lumioSnapshot("allocation_loading_light") { Ring(AllocationState.Loading) }

    @Test
    fun empty() = compose.lumioSnapshot("allocation_empty") { Ring(AllocationState.Empty) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun emptyLight() = compose.lumioSnapshot("allocation_empty_light") { Ring(AllocationState.Empty) }

    @Test
    fun hidden() = compose.lumioSnapshot("allocation_hidden") { Ring(AllocationState.Hidden) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun hiddenLight() = compose.lumioSnapshot("allocation_hidden_light") { Ring(AllocationState.Hidden) }

    @Test
    fun readyThreeAssets() = compose.lumioSnapshot("allocation_ready_three") { Ring(ready(THREE_ASSETS)) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun readyThreeAssetsLight() = compose.lumioSnapshot("allocation_ready_three_light") { Ring(ready(THREE_ASSETS)) }

    @Test
    fun readyOneAsset() = compose.lumioSnapshot("allocation_ready_one") { Ring(ready(ONE_ASSET)) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun readyOneAssetLight() = compose.lumioSnapshot("allocation_ready_one_light") { Ring(ready(ONE_ASSET)) }

    @Test
    fun readyWithOther() = compose.lumioSnapshot("allocation_ready_other") { Ring(ready(WITH_OTHER)) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun readyWithOtherLight() = compose.lumioSnapshot("allocation_ready_other_light") { Ring(ready(WITH_OTHER)) }

    @Test
    fun selected() = compose.lumioSnapshot("allocation_selected") { Ring(ready(THREE_ASSETS), selected = "usdc") }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun selectedLight() = compose.lumioSnapshot("allocation_selected_light") { Ring(ready(THREE_ASSETS), selected = "usdc") }

    @Test
    fun smallReadyWithoutTotal() =
        compose.lumioSnapshot("allocation_small_ready") { Ring(ready(THREE_ASSETS), diameter = 40.dp, showCentre = false) }

    /** The ring alone, centred in the page gutter, so each golden shows the state and nothing else. */
    @Composable
    private fun Ring(
        state: AllocationState,
        selected: String? = null,
        diameter: Dp = 232.dp,
        showCentre: Boolean = true,
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(LumioGutter), contentAlignment = Alignment.Center) {
            AllocationRing(state = state, diameter = diameter, selectedAssetId = selected, showCentre = showCentre)
        }
    }

    /** The ready state of [assets], each coloured as the page colours its id. */
    @Composable
    private fun ready(assets: List<Pair<String, Double>>): AllocationState.Ready {
        val background = MaterialTheme.colorScheme.background
        return AllocationState.Ready(assets.map { (id, usd) -> AllocationSlice(id, usd, AllocationPalette.colorOf(id, background)) })
    }

    private companion object {
        val THREE_ASSETS = listOf("eth" to 6200.0, "usdc" to 3100.0, "sol" to 1300.0)
        val ONE_ASSET = listOf("eth" to 1234.56)

        /** Three large holdings and five small ones, which together are under 4 % and so merge into Other. */
        val WITH_OTHER = listOf(
            "eth" to 5200.0,
            "usdc" to 2600.0,
            "sol" to 1100.0,
            "dust-1" to 150.0,
            "dust-2" to 90.0,
            "dust-3" to 60.0,
            "dust-4" to 40.0,
            "dust-5" to 30.0,
        )
    }
}
