package app.lumio.ui.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.SentencePicker
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.ActivitySort
import app.lumio.transactions.ActivityText
import app.lumio.transactions.TxFeedItem
import app.lumio.transactions.sentence
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirFilterSentence
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTextAction
import java.time.ZoneId

/**
 * The activity of every account on one side, newest or largest first. The filter sentence says what it shows, and spam is left out and counted until
 * the owner shows it. [items] is null until the first page is read. The rows are grouped under their day when the order is by date.
 */
@Composable
fun ActivityList(
    items: List<TxFeedItem>?,
    filter: ActivityFilter,
    onFilter: (ActivityFilter) -> Unit,
    families: List<ChainFamily>,
    names: Map<String, String>,
    spamHidden: Int,
    showingSpam: Boolean,
    onShowSpam: () -> Unit,
    showBalance: Boolean,
    zone: ZoneId,
    nowMs: Long,
    onOpen: (TxFeedItem) -> Unit,
) {
    val palette = LocalClearAirPalette.current
    var picker by remember { mutableStateOf<SentencePicker?>(null) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.xxl)) {
        ClearAirFilterSentence(
            parts = filter.sentence(),
            onPick = { picker = it },
            modifier = Modifier.padding(top = ClearAirSpacing.sm),
        )
        if (spamHidden > 0 && !showingSpam) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val noun = if (spamHidden == 1) "spam transaction" else "spam transactions"
                Text(text = "$spamHidden $noun hidden", style = ClearAirType.body, color = palette.ink2, modifier = Modifier.weight(1f))
                ClearAirTextAction(text = "Show", onClick = onShowSpam)
            }
        }
        when {
            items == null -> Unit
            items.isEmpty() -> Text(
                text = "No activity yet. Your first receive will appear here.",
                style = ClearAirType.body,
                color = palette.ink2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.lg),
            )
            filter.sort == ActivitySort.NEWEST -> {
                var section: String? = null
                for (item in items) {
                    val day = ActivityText.section(item.tx.timestampMs, nowMs, zone)
                    if (day != section) {
                        section = day
                        ClearAirEyebrow(text = day, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
                    }
                    ActivityRowItem(item = item, names = names, zone = zone, shown = showBalance, onClick = { onOpen(item) })
                }
            }
            else -> for (item in items) {
                ActivityRowItem(item = item, names = names, zone = zone, shown = showBalance, onClick = { onOpen(item) })
            }
        }
    }
    ClearAirSheet(visible = picker == SentencePicker.CHAIN, onClose = { picker = null }) {
        ClearAirChoiceSheet(
            title = "Chains",
            groups = listOf(ChoiceGroup(null, chainChoices(families, filter) { picker = null; onFilter(it) })),
        )
    }
    ClearAirSheet(visible = picker == SentencePicker.SORT, onClose = { picker = null }) {
        ClearAirChoiceSheet(
            title = "Sort Activity",
            groups = listOf(ChoiceGroup(null, sortChoices(filter) { picker = null; onFilter(it) })),
        )
    }
}

/** The chain choices of the activity: all chains, then each family that has history on this side. */
private fun chainChoices(families: List<ChainFamily>, filter: ActivityFilter, pick: (ActivityFilter) -> Unit): List<ChoiceRow> =
    listOf(ChoiceRow(title = "All Chains", selected = filter.chain == null, onPick = { pick(filter.copy(chain = null)) })) +
        families.map { family ->
            ChoiceRow(title = family.label, selected = filter.chain == family, onPick = { pick(filter.copy(chain = family)) })
        }

/** The order choices of the activity: newest first, or largest first. */
private fun sortChoices(filter: ActivityFilter, pick: (ActivityFilter) -> Unit): List<ChoiceRow> = listOf(
    ChoiceRow(
        title = "Newest First",
        detail = "By date",
        selected = filter.sort == ActivitySort.NEWEST,
        onPick = { pick(filter.copy(sort = ActivitySort.NEWEST)) },
    ),
    ChoiceRow(
        title = "Largest First",
        detail = "By amount",
        selected = filter.sort == ActivitySort.LARGEST,
        onPick = { pick(filter.copy(sort = ActivitySort.LARGEST)) },
    ),
)
