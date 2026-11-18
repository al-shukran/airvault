package app.lumio.ui.wallets

import android.view.Choreographer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.lensly.ui.navigation.NavReducedMotion
import app.lumio.network.NetworkMode
import app.lumio.wallets.AccountChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The spring of a sheet's rise and its fall: critically damped, so it settles without overshoot. */
private val SheetSpring = spring<Float>(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = 0.001f)

/** The alpha of the scrim under a sheet at full height: it dims what lies beneath by 40 per cent. */
private const val SCRIM_ALPHA = 0.4f

/** How far the first sheet sits below the top of the content, where the Wallets home shows dimmed. Each sheet above it sits [SHEET_STEP] lower. */
private val SHEET_TOP = 40.dp

/** How much lower each sheet sits than the one beneath it, so the sheet under it shows its top edge. */
private val SHEET_STEP = 16.dp

/** The height of the strip at the top of a sheet that a drag starts from. */
private val GRAB_HEIGHT = 24.dp

/** A release faster than this many sheet heights a second settles the sheet the way it was flung. */
private const val SHEET_FLING = 0.5f

/** A screen that rises as a sheet over the Wallets home. */
internal sealed interface WalletSheet {
    /** A paired wallet and its accounts. */
    data class Wallet(val walletId: String) : WalletSheet

    /** One account of a wallet, by its chain and its place among the accounts of that chain. */
    data class Account(val walletId: String, val chain: AccountChain, val index: Int) : WalletSheet
}

/**
 * One open sheet. [progress] is its place: 0 is below the screen and 1 is open. [heightPx] is the height the sheet is laid out in, which a drag turns
 * into progress. [closing] is set while the sheet leaves, so the system Back closes the sheet beneath it instead.
 */
internal class OpenSheet(val sheet: WalletSheet) {
    val progress = Animatable(0f)
    var heightPx = 0f
    var closing by mutableStateOf(false)
}

/**
 * The sheets over the Wallets home, in the order they opened. The home stays under them, still and dimmed. [open] rises a sheet; [dragBy] and [release]
 * move the top one with the finger; [closeTop] and [closeSheet] drop one with its exit. Each open vault has its own, and [wipe] drops them when it locks.
 */
internal class WalletSheets(
    private val reducedMotion: () -> Boolean = { NavReducedMotion.reduced },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val stack = mutableStateListOf<OpenSheet>()
    private var side: NetworkMode? = null

    /** The open sheets, bottom first. A sheet that is leaving stays in the list until its exit has run. */
    val sheets: List<OpenSheet> get() = stack

    /** Whether a sheet is open that is not leaving, so the system Back has one to close. */
    val closable: Boolean get() = stack.any { !it.closing }

    /** Rises [sheet] over the sheets beneath it. Under reduced motion it is open at once. */
    fun open(sheet: WalletSheet) {
        val opened = OpenSheet(sheet)
        stack.add(opened)
        scope.launch { glide(opened, target = 1f, velocity = 0f) }
    }

    /** Closes the top sheet that is not leaving. Returns false when there is none. */
    fun closeTop(): Boolean {
        val top = stack.lastOrNull { !it.closing } ?: return false
        closeSheet(top)
        return true
    }

    /** Lowers [opened] off the screen, then drops it. [velocity] is the speed it leaves at, in sheet heights a second. */
    fun closeSheet(opened: OpenSheet, velocity: Float = 0f) {
        if (opened !in stack) return
        opened.closing = true
        scope.launch {
            glide(opened, target = 0f, velocity = velocity)
            stack.remove(opened)
        }
    }

    /** The finger takes [opened] again, so a sheet that was leaving follows the drag. */
    fun grab(opened: OpenSheet) {
        opened.closing = false
    }

    /**
     * Moves [opened] by [deltaPx] under the finger, down is positive, and keeps it between its closed and open places. Returns the distance it moved, so a
     * list can pass the rest of a drag on.
     */
    fun dragBy(opened: OpenSheet, deltaPx: Float): Float {
        val height = opened.heightPx
        if (height <= 0f || deltaPx == 0f) return 0f
        val before = opened.progress.value
        val after = (before - deltaPx / height).coerceIn(0f, 1f)
        if (after == before) return 0f
        opened.closing = false
        scope.launch { opened.progress.snapTo(after) }
        return (before - after) * height
    }

    /**
     * The finger let go of [opened] moving at [velocityDownPx]. The sheet settles open or closes by its place and that speed, as [sheetSettlesOpen] decides.
     */
    fun release(opened: OpenSheet, velocityDownPx: Float) {
        val height = opened.heightPx
        if (height <= 0f) return
        val velocity = -velocityDownPx / height
        if (sheetSettlesOpen(opened.progress.value, velocity)) {
            opened.closing = false
            scope.launch { glide(opened, target = 1f, velocity = velocity) }
        } else {
            closeSheet(opened, velocity)
        }
    }

    /** Drops every sheet at once, with no exit. */
    fun clear() {
        stack.clear()
    }

    /**
     * Keeps the sheets to the side they were opened on. A change of side drops them, since the accounts they show are the ones of the side before.
     */
    fun keepSide(mode: NetworkMode) {
        if (side != null && side != mode) clear()
        side = mode
    }

    /** Drops the sheets and stops their exits. [LumioVault] calls it when the vault locks. */
    fun wipe() {
        clear()
        scope.cancel()
    }

    private suspend fun glide(opened: OpenSheet, target: Float, velocity: Float) {
        if (reducedMotion()) {
            opened.progress.snapTo(target)
        } else {
            withContext(SheetFrames) { opened.progress.animateTo(target, SheetSpring, initialVelocity = velocity) }
        }
    }
}

/**
 * The frame clock of the sheets' springs. The sheets run off the composition, which is what supplies Compose's own frame clock, so each frame of a
 * spring is a Choreographer frame on the main thread.
 */
private object SheetFrames : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { nanos -> continuation.resumeWith(runCatching { onFrame(nanos) }) }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }
}

/**
 * Whether a sheet let go at [progress] (0 below the screen, 1 open) and moving at [velocity] (sheet heights a second, rising positive) settles open. A fling
 * decides; without one, the sheet settles open from half way up.
 */
internal fun sheetSettlesOpen(progress: Float, velocity: Float): Boolean = when {
    velocity > SHEET_FLING -> true
    velocity < -SHEET_FLING -> false
    else -> progress >= 0.5f
}

/**
 * The sheets over the content beneath them. Each sheet has a scrim under it that dims what lies beneath by the sheet's progress, and touches on the scrim
 * go nowhere. The system Back closes the top sheet. [content] draws the screen of one sheet.
 */
@Composable
internal fun WalletSheetHost(sheets: WalletSheets, content: @Composable (OpenSheet) -> Unit) {
    BackHandler(enabled = sheets.closable) { sheets.closeTop() }
    val scrim = MaterialTheme.colorScheme.scrim
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenPx = with(LocalDensity.current) { maxHeight.toPx() }
        sheets.sheets.forEachIndexed { depth, opened ->
            key(opened) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(scrim, alpha = opened.progress.value * SCRIM_ALPHA) }
                        .consumeTouches(),
                )
                SheetSurface(sheets, opened, depth, screenPx, marked = depth == sheets.sheets.lastIndex) { content(opened) }
            }
        }
    }
}

/**
 * One sheet: its strip to drag by, and its screen under it. It rises from the bottom edge on [OpenSheet.progress], and its scroll is handed to the sheet
 * (see [SheetDrag]). The screen takes no overscroll of its own, so a drag down at the top of a list reaches the sheet. [marked] is set on the top sheet
 * only: a sheet beneath shows its edge, and its grab mark stays hidden under the sheet above.
 */
@Composable
private fun SheetSurface(sheets: WalletSheets, opened: OpenSheet, depth: Int, screenPx: Float, marked: Boolean, content: @Composable () -> Unit) {
    val top = SHEET_TOP + SHEET_STEP * depth
    opened.heightPx = screenPx - with(LocalDensity.current) { top.toPx() }
    val scroll = remember(sheets, opened) { SheetDrag(sheets, opened) }
    val shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = top)
            .offset { IntOffset(0, ((1f - opened.progress.value) * opened.heightPx).roundToInt()) }
            .clip(shape)
            .background(MaterialTheme.colorScheme.background),
    ) {
        GrabStrip(sheets, opened, marked)
        Box(Modifier.fillMaxWidth().weight(1f).nestedScroll(scroll)) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) { content() }
        }
    }
}

/** The strip at the top of a sheet with its grab mark, drawn when [marked]. A drag on it moves the sheet with the finger, and a release settles it. */
@Composable
private fun GrabStrip(sheets: WalletSheets, opened: OpenSheet, marked: Boolean) {
    val drag = rememberDraggableState { delta -> sheets.dragBy(opened, delta) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(GRAB_HEIGHT)
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStarted = { sheets.grab(opened) },
                onDragStopped = { velocity -> sheets.release(opened, velocity) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (marked) 0.4f else 0f)),
        )
    }
}

/**
 * The scroll of a sheet's screen, handed to the sheet. While the sheet is not open, an upward drag takes it up before the list scrolls. A downward drag
 * that the list does not take, because the list is at its top, moves the sheet down. A fling that the sheet has not taken settles the sheet.
 */
private class SheetDrag(private val sheets: WalletSheets, private val opened: OpenSheet) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        return Offset(0f, sheets.dragBy(opened, available.y))
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        return Offset(0f, sheets.dragBy(opened, available.y))
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (opened.progress.value >= 1f) return Velocity.Zero
        sheets.release(opened, available.y)
        return Velocity(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (available.y <= 0f) return Velocity.Zero
        sheets.release(opened, available.y)
        return Velocity(0f, available.y)
    }
}

/** Takes every touch on this area, so that nothing beneath a sheet takes it. */
private fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown().consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
