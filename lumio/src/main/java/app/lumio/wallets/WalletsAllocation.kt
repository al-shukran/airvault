package app.lumio.wallets

import androidx.compose.ui.graphics.Color
import app.lumio.ui.kit.AllocationPalette
import app.lumio.ui.kit.AllocationSlice
import app.lumio.ui.kit.AllocationState

/**
 * The allocation of all wallets on a side, as the Wallets home's ring draws it (section 1.6). Each asset is one slice, its value in dollars summed over the
 * wallets that hold it, and the ring merges the small slices into Other. The ring is Hidden when the owner hides totals or balances, so no segment shows,
 * and Empty when no holding has a value.
 */
internal object WalletsAllocation {
    /** The state of the ring for [view] on a page of [background]. [shown] is false when the owner hides totals or balances. */
    fun state(view: WalletsView, shown: Boolean, background: Color): AllocationState {
        if (!shown) return AllocationState.Hidden
        val slices = view.assetsOf(null)
            .groupBy { it.id }
            .map { (id, same) -> AllocationSlice(id, same.sumOf { it.usd ?: 0.0 }, AllocationPalette.colorOf(id, background)) }
            .filter { it.usd > 0.0 }
        return if (slices.isEmpty()) AllocationState.Empty else AllocationState.Ready(slices)
    }
}
