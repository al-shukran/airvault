package app.lensly.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.chainIconDrawables
import app.lensly.ui.components.tokenIconDrawables
import app.lensly.ui.settings.animatorsDisabled
import app.lensly.ui.theme.LenslyLogoBackdrop
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import kotlin.math.roundToInt

/** The test tag of the logo collage, so a test can find it. */
internal const val HOME_COLLAGE_TAG = "home-collage"

/** Three rows of eight logos. */
private const val COLLAGE_ROWS = 3
private const val COLLAGE_PER_ROW = 8

/**
 * How many times a row lays out its logos. A drift moves a row by one set, so three sets cover the screen at every point of the drift, and
 * a row is never left short at its far end.
 */
private const val COLLAGE_COPIES = 3

/** A logo is 40 dp across and sits 30 dp after the one before it, so each overlaps the next. Rows are 32 dp apart. */
private val LOGO_SIZE = 40.dp
private val LOGO_STEP = 30.dp
private val ROW_STEP = 32.dp

/** How long a row takes to drift past one set of its logos, and how much longer each row after the first takes. */
private const val DRIFT_MS = 36_000
private const val DRIFT_STEP_MS = 6_000

/** A row that does not move: its place is always the start, and no transition runs. */
private val StillProgress: State<Float> = object : State<Float> {
    override val value: Float get() = 0f
}

/**
 * The bundled network and token logos as one list: a chain, then a token, and so on, until one of the two runs out. The collage takes its
 * rows from the front of this list.
 */
internal fun interleaveLogos(chains: Collection<Int>, tokens: Collection<Int>): List<Int> = buildList {
    val chainLogos = chains.iterator()
    val tokenLogos = tokens.iterator()
    while (chainLogos.hasNext() || tokenLogos.hasNext()) {
        if (chainLogos.hasNext()) add(chainLogos.next())
        if (tokenLogos.hasNext()) add(tokenLogos.next())
    }
}

/**
 * The logo collage: the bundled network and token logos as overlapping circles in staggered rows. The rows drift slowly, alternately left
 * and right, unless the system animations are off (the animator duration scale is 0). The caption counts the catalog's networks and
 * tokens. A tap opens the Networks list through [onNetworks].
 */
@Composable
internal fun LogoCollageSection(
    networkCount: Int,
    tokenCount: Int,
    onNetworks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember { interleaveLogos(chainIconDrawables.values, tokenIconDrawables.values).chunked(COLLAGE_PER_ROW).take(COLLAGE_ROWS) }
    val context = LocalContext.current
    val still = remember(context) { animatorsDisabled(context) }
    val caption = "${counted(networkCount, "network")} · ${counted(tokenCount, "token")}"
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_STEP * COLLAGE_ROWS)
                .clipToBounds()
                .testTag(HOME_COLLAGE_TAG)
                .semantics { contentDescription = "Supported networks and tokens, $caption. Opens the networks list." }
                .clickable(role = Role.Button, onClickLabel = "Open Networks", onClick = onNetworks),
        ) {
            Column {
                rows.forEachIndexed { index, logos -> CollageRow(logos = logos, row = index, still = still) }
            }
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = caption.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Row [row] of logos. Odd rows drift right and even rows left, each at its own pace, so the rows do not move in step. Its logos are laid
 * out [COLLAGE_COPIES] times, so a drift of one set brings the row back to where it began, with no seam, and the row still covers the
 * screen. Its progress is read in the layout phase, so a frame moves the row without recomposing it.
 */
@Composable
private fun CollageRow(logos: List<Int>, row: Int, still: Boolean) {
    val reverse = row % 2 == 1
    val setWidth = with(LocalDensity.current) { (LOGO_STEP * logos.size).toPx() }
    val progress = if (still) StillProgress else drift(durationMs = DRIFT_MS + row * DRIFT_STEP_MS)
    Row(
        modifier = Modifier
            .offset {
                val fraction = progress.value
                val x = if (reverse) -(1f - fraction) * setWidth else -fraction * setWidth
                IntOffset(x.roundToInt(), 0)
            }
            .wrapContentWidth(Alignment.Start, unbounded = true),
    ) {
        repeat(COLLAGE_COPIES) {
            logos.forEach { logo -> CollageLogo(logo) }
        }
    }
}

/** The progress of a row's drift, from 0 to 1 over [durationMs], then again from 0. */
@Composable
private fun drift(durationMs: Int): State<Float> {
    val transition = rememberInfiniteTransition(label = "collage")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = durationMs, easing = LinearEasing)),
        label = "collage-drift",
    )
}

/** One logo: a light disc with the logo centred in it. Its cell is narrower than the disc, so each logo overlaps its neighbours. */
@Composable
private fun CollageLogo(@DrawableRes logo: Int) {
    Box(
        modifier = Modifier.size(LOGO_STEP, ROW_STEP),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(LOGO_SIZE)
                .clip(CircleShape)
                .background(LenslyLogoBackdrop)
                .border(1.dp, LenslyOutline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = logo),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
