package app.lensly.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslyProfileBlue
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslySurfaceHigh
import app.lensly.ui.theme.LenslyWarning
import app.lensly.ui.wallet.WALLET_GUTTER
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The span of the chart: the last seven days or the last thirty, today included. */
internal enum class ChartWindow(val days: Int, val label: String) {
    WEEK(7, "7 days"),
    MONTH(30, "30 days"),
}

/** What the chart's bars are stacked by: the outcome of each record, or the network it was signed on. */
internal enum class ChartBreakdown(val label: String) {
    STATUS("Status"),
    APP("App"),
}

/** The most networks the App breakdown names. Every other network, and a record with no chain, counts as Other. */
internal const val CHART_NETWORK_LIMIT = 3

/** The key of the Other segment. A network's key is its chain id, so the two cannot collide. */
private const val OTHER_KEY = "other"

/** One stacked segment: its key, its legend label, and how many records it counts over the window. */
internal data class ChartSegment(val key: String, val label: String, val count: Int)

/** One day of the chart: its date, and how many records of each segment fell on it, in the order of the chart's segments. */
internal data class ChartDay(val date: LocalDate, val counts: List<Int>)

/** The chart over a window: its days oldest first, and its segments in legend order. */
internal data class ActivityChart(val days: List<ChartDay>, val segments: List<ChartSegment>) {
    /** The number of records the window holds. */
    val total: Int get() = segments.sumOf { it.count }
}

/**
 * Buckets [records] for the chart: one day per date of [window], ending with the day of [nowMs] in [zone], each record counted under its
 * segment for [breakdown]. A record outside the window, or after today, is left out.
 *
 * Status has a segment for each outcome, in the order signed, refused, cancelled, and each is listed even at zero. App names the
 * [CHART_NETWORK_LIMIT] networks with the most records in the window, the most first and ties by name, and counts the rest as Other,
 * which is listed only when it holds a record.
 */
internal fun activityChart(
    records: List<ReviewRecord>,
    nowMs: Long,
    window: ChartWindow,
    breakdown: ChartBreakdown,
    zone: ZoneId,
): ActivityChart {
    val today = dayOf(nowMs, zone)
    val first = today.minusDays(window.days - 1L)
    val inWindow = records.filter { record ->
        val day = dayOf(record.atMs, zone)
        !day.isBefore(first) && !day.isAfter(today)
    }
    val segments = when (breakdown) {
        ChartBreakdown.STATUS -> ReviewOutcome.entries.map { outcome ->
            ChartSegment(key = outcome.name, label = outcomeLabel(outcome), count = inWindow.count { it.outcome == outcome })
        }
        ChartBreakdown.APP -> appSegments(inWindow)
    }
    val column = segments.withIndex().associate { (index, segment) -> segment.key to index }
    val counts = Array(window.days) { IntArray(segments.size) }
    inWindow.forEach { record ->
        val key = when (breakdown) {
            ChartBreakdown.STATUS -> record.outcome.name
            ChartBreakdown.APP -> record.chainId?.toString() ?: OTHER_KEY
        }
        // A network that is not named counts as Other. Other is listed whenever such a record exists, so the lookup always finds it.
        val index = column[key] ?: column.getValue(OTHER_KEY)
        counts[ChronoUnit.DAYS.between(first, dayOf(record.atMs, zone)).toInt()][index]++
    }
    val days = counts.mapIndexed { offset, row ->
        ChartDay(date = first.plusDays(offset.toLong()), counts = row.toList())
    }
    return ActivityChart(days = days, segments = segments)
}

/** The App segments of [records]: the networks with the most records, then Other when any record is left for it. */
private fun appSegments(records: List<ReviewRecord>): List<ChartSegment> {
    val named = records
        .filter { it.chainId != null }
        .groupBy { it.chainId }
        .map { (chainId, group) ->
            ChartSegment(
                key = chainId.toString(),
                label = group.firstNotNullOfOrNull { it.networkName } ?: "Chain $chainId",
                count = group.size,
            )
        }
        .sortedWith(compareByDescending<ChartSegment> { it.count }.thenBy { it.label })
        .take(CHART_NETWORK_LIMIT)
    val otherCount = records.size - named.sumOf { it.count }
    return if (otherCount > 0) named + ChartSegment(OTHER_KEY, "Other", otherCount) else named
}

/** The day of [atMs] in [zone]. */
private fun dayOf(atMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

/** The test tag of the chart's tappable area, so a test can find it. */
internal const val HOME_CHART_TAG = "home-chart"

/** The height of the bars. */
private val CHART_HEIGHT = 120.dp

/** The height of the empty chart card, which is the chart's card at a compact height. */
private val EMPTY_CHART_HEIGHT = 120.dp

/** The share of a day's slot a bar fills. The rest is the gap between two bars. */
private const val BAR_FILL = 0.62f

/** The colour of a status segment: signed, refused, or cancelled. */
private fun statusColour(key: String): Color = when (key) {
    ReviewOutcome.SIGNED.name -> LenslySuccess
    ReviewOutcome.REFUSED.name -> LenslyError
    else -> LenslyMuted
}

/** The colour of an App segment: the first three networks in turn, and Other in grey. */
private val NETWORK_COLOURS = listOf(LenslySuccess, LenslyProfileBlue, LenslyWarning)

private fun segmentColour(breakdown: ChartBreakdown, index: Int, key: String): Color = when (breakdown) {
    ChartBreakdown.STATUS -> statusColour(key)
    ChartBreakdown.APP -> if (key == OTHER_KEY) LenslyMuted else NETWORK_COLOURS[index % NETWORK_COLOURS.size]
}

/**
 * SIGNING ACTIVITY: the records of the last [ChartWindow] as stacked bars, one per day, drawn on the canvas. The heading carries the
 * window toggle. Under the bars sit the legend, with each segment's count, and the breakdown toggle. A tap on the bars and the legend
 * opens the Activity screen through [onOpen].
 *
 * [records] is null until the read finishes, and then the heading alone shows. With no record at all, a calm line says that the
 * activity will appear here.
 */
@Composable
internal fun ActivityChartSection(
    records: List<ReviewRecord>?,
    nowMs: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var window by rememberSaveable { mutableStateOf(ChartWindow.MONTH) }
    var breakdown by rememberSaveable { mutableStateOf(ChartBreakdown.STATUS) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER)) {
        SectionHeader(title = "Signing activity") {
            if (!records.isNullOrEmpty()) {
                SegmentedToggle(options = ChartWindow.entries, selected = window, label = { it.label }, onSelect = { window = it })
            }
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        if (records != null) {
            if (records.isEmpty()) {
                ChartPlaceholder()
            } else {
                val chart = remember(records, nowMs, window, breakdown) {
                    activityChart(records, nowMs, window, breakdown, ZoneId.systemDefault())
                }
                ChartCard(
                    chart = chart,
                    window = window,
                    breakdown = breakdown,
                    onOpen = onOpen,
                    onBreakdown = { breakdown = it },
                )
            }
        }
    }
}

/** The card around the chart: the tappable bars and legend, then the breakdown toggle. */
@Composable
private fun ChartCard(
    chart: ActivityChart,
    window: ChartWindow,
    breakdown: ChartBreakdown,
    onOpen: () -> Unit,
    onBreakdown: (ChartBreakdown) -> Unit,
) {
    val haptics = rememberKeyHaptics()
    val shape = RoundedCornerShape(LenslyRadius.lg)
    val summary = chartSummary(chart, window, breakdown)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyOutline, shape)
            .padding(LenslySpacing.md),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HOME_CHART_TAG)
                .semantics(mergeDescendants = true) { contentDescription = summary }
                .tapHaptic(haptics)
                .clickable(role = Role.Button, onClickLabel = "Open Activity", onClick = onOpen),
        ) {
            ChartBars(chart = chart, breakdown = breakdown)
            Spacer(Modifier.height(LenslySpacing.xs))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = dayLabel(chart.days.first().date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "TODAY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LenslySpacing.sm))
            ChartLegend(segments = chart.segments, breakdown = breakdown)
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        SegmentedToggle(options = ChartBreakdown.entries, selected = breakdown, label = { it.label }, onSelect = onBreakdown)
    }
}

/** The bars, one per day, each stacked from the bottom in the order of the segments, over a baseline. */
@Composable
private fun ChartBars(chart: ActivityChart, breakdown: ChartBreakdown) {
    Canvas(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val peak = chart.days.maxOf { day -> day.counts.sum() }.coerceAtLeast(1)
        val slot = size.width / chart.days.size
        val barWidth = slot * BAR_FILL
        val unit = size.height / peak
        val corner = CornerRadius(barWidth / 4f)
        chart.days.forEachIndexed { dayIndex, day ->
            var top = size.height
            day.counts.forEachIndexed { segmentIndex, count ->
                if (count > 0) {
                    val height = count * unit
                    top -= height
                    drawRoundRect(
                        color = segmentColour(breakdown, segmentIndex, chart.segments[segmentIndex].key),
                        topLeft = Offset(dayIndex * slot + (slot - barWidth) / 2f, top),
                        size = Size(barWidth, height),
                        cornerRadius = corner,
                    )
                }
            }
        }
        drawLine(
            color = LenslyOutline,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** How many legend items share a row. Two columns keep a long network name whole, where four across would cut it short. */
private const val LEGEND_COLUMNS = 2

/** The legend: a dot, the name and the count of each segment, in the order of the segments, [LEGEND_COLUMNS] to a row. */
@Composable
private fun ChartLegend(segments: List<ChartSegment>, breakdown: ChartBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs)) {
        segments.withIndex().toList().chunked(LEGEND_COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (index, segment) ->
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(segmentColour(breakdown, index, segment.key)),
                        )
                        Spacer(Modifier.width(LenslySpacing.xs))
                        Text(
                            text = segment.label,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = " ${segment.count}",
                            style = MaterialTheme.typography.labelMedium,
                            color = LenslyOnSurface,
                            maxLines = 1,
                        )
                    }
                }
                if (row.size < LEGEND_COLUMNS) Spacer(Modifier.weight((LEGEND_COLUMNS - row.size).toFloat()))
            }
        }
    }
}

/**
 * The line that stands in for the chart before any record: a dashed baseline, and the words that say the activity will appear here.
 */
@Composable
private fun ChartPlaceholder() {
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(EMPTY_CHART_HEIGHT)
            .clip(shape)
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyOutline, shape)
            .padding(LenslySpacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            drawLine(
                color = LenslyOutline,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())),
            )
        }
        Text(
            text = "No signatures yet — your activity chart appears here",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A segmented control: the options in a pill, the chosen one lit. Each option is a tab with its own haptic tap. */
@Composable
private fun <T> SegmentedToggle(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = Modifier
            .clip(LenslyPillShape)
            .border(1.dp, LenslyOutline, LenslyPillShape)
            .background(LenslySurfaceContainer)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                text = label(option).uppercase(),
                modifier = Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(LenslyPillShape)
                    .background(if (isSelected) LenslySurfaceHigh else Color.Transparent)
                    .semantics { this.selected = isSelected }
                    .tapHaptic(haptics)
                    .clickable(role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = LenslySpacing.sm),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) LenslyOnSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * What a screen reader hears of the chart: the window, the breakdown, and each segment's count. The tap that opens Activity is named
 * on the chart itself.
 */
private fun chartSummary(chart: ActivityChart, window: ChartWindow, breakdown: ChartBreakdown): String {
    val by = if (breakdown == ChartBreakdown.APP) "by network" else "by status"
    val counts = chart.segments.joinToString(", ") { "${it.label} ${it.count}" }
    return "Signing activity, last ${window.days} days, $by: $counts."
}

/** The date label under the first bar, such as "OCT 14", in the device's language. */
private fun dayLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())).uppercase()
