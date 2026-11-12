package app.lensly.ui.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

/** The shortest time the tiles stay hidden after a tap, in milliseconds. */
const val HIDE_MIN_MS = 100L

/** The longest time the tiles stay hidden after a tap, in milliseconds. Both ends of the range are possible. */
const val HIDE_MAX_MS = 5_000L

/**
 * The value tiles of a dice or coin entry: the face on each tile, and whether the tiles are hidden.
 *
 * A tap returns the face on the tapped tile and hides the tiles. Hiding reorders the tiles with a Fisher–Yates
 * shuffle drawn from [random], so the layout shown after the reveal is not the layout the tap was made on. The
 * caller waits [hideDelayMs] and then calls [reveal]. While hidden, [tap] and [hide] do nothing.
 */
class EntryTiles(mode: EntropyMode, private val random: SecureRandom) {
    /** The face on each tile, by tile index. Tile 0 is the first tile in the grid. */
    var faces: List<Char> by mutableStateOf(mode.symbols.toList())
        private set

    /** True from a tap until [reveal]. */
    var hidden: Boolean by mutableStateOf(false)
        private set

    /** Records the face on tile [index] and hides the tiles. Returns that face, or null while hidden. */
    fun tap(index: Int): Char? {
        if (hidden) return null
        val face = faces[index]
        hide()
        return face
    }

    /** Hides and reorders the tiles, for a tap or an Undo. Returns false, and does nothing, while already hidden. */
    fun hide(): Boolean {
        if (hidden) return false
        hidden = true
        faces = shuffled(faces)
        return true
    }

    /** Shows the tiles again. */
    fun reveal() {
        hidden = false
    }

    /** How long the tiles stay hidden: uniform over [HIDE_MIN_MS] to [HIDE_MAX_MS], as 100 + nextInt(4901). */
    fun hideDelayMs(): Long = HIDE_MIN_MS + random.nextInt((HIDE_MAX_MS - HIDE_MIN_MS + 1).toInt())

    /** Fisher–Yates over a copy of [tiles]: each position from the end swaps with a position at or before it. */
    private fun shuffled(tiles: List<Char>): List<Char> {
        val out = tiles.toMutableList()
        for (i in out.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val held = out[i]
            out[i] = out[j]
            out[j] = held
        }
        return out
    }
}
