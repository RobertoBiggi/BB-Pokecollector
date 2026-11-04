@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.stats

import androidx.compose.ui.res.pluralStringResource
import com.robertobiggi.cardcollection.ui.common.shortLabel
import com.robertobiggi.cardcollection.ui.common.runSummaryText
import com.robertobiggi.cardcollection.ui.common.labelRes
import com.robertobiggi.cardcollection.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.data.repo.LibraryAssembler
import com.robertobiggi.cardcollection.core.stats.StatsSummary
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.theme.MonoStyle

/**
 * Computes the summary of a group of cards.
 * @param cards List<CardUi> - cards of the group.
 * @return StatsSummary - statistics of the group.
 */
private fun summaryOf(cards: List<CardUi>): StatsSummary = LibraryAssembler.statsOf(cards)

/**
 * Statistics: completion, collection value, progress by language and by series.
 * @param vm MainViewModel - state.
 * @return Unit
 */
@Composable
fun StatsScreen(vm: MainViewModel) {
    val library by vm.library.collectAsStateWithLifecycle()
    val name by vm.collectionName.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val visibility by vm.priceVisibility.collectAsStateWithLifecycle()
    val cards = library.orEmpty()
    val total = remember(cards) { summaryOf(cards) }
    val byLanguage = remember(cards) {
        cards.groupBy { it.language }.entries.sortedBy { it.key.ordinal }.map { it.key to summaryOf(it.value) }.filter { it.second.slots > 0 }
    }
    val otherSerie = stringResource(R.string.stats_other_serie)
    val bySerie = remember(cards, otherSerie) {
        cards.groupBy { it.card.serieName ?: otherSerie }
            .entries.sortedBy { e -> e.value.minOfOrNull { it.card.releaseDate ?: "9999" } }
            .map { it.key to summaryOf(it.value) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.stats_title, name.orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis) })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { total.completionPercent() / 100f },
                                modifier = Modifier.size(96.dp),
                                strokeWidth = 10.dp,
                            )
                            Text("${total.completionPercent()}%", fontWeight = FontWeight.ExtraBold)
                        }
                        Column {
                            Text(stringResource(R.string.stats_completion), style = MaterialTheme.typography.labelMedium)
                            Text("${total.owned} / ${total.slots}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                            Text(stringResource(R.string.stats_completion_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!visibility.showsNone) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.stats_value), style = MaterialTheme.typography.titleMedium)
                        if (visibility.cardmarket) {
                            ValueRow(stringResource(R.string.price_cardmarket), Format.eur(total.valueEur), total.pricedEur, total.owned)
                        }
                        if (visibility.cardTrader) {
                            ValueRow(stringResource(R.string.price_cardtrader), Format.eur(total.valueCardTrader), total.pricedCardTrader, total.owned)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(stringResource(R.string.stats_copies), "${total.copies}", stringResource(R.string.stats_copies_hint), Modifier.weight(1f))
                    StatTile(
                        stringResource(R.string.stats_missing),
                        if (visibility.cardmarket) Format.eur(total.missingEur) else "${total.missing}",
                        if (visibility.cardmarket) pluralStringResource(R.plurals.missing_count, total.missing, total.missing) else stringResource(R.string.stats_missing_hint),
                        Modifier.weight(1f),
                    )
                }
            }
            item { Bars(stringResource(R.string.stats_by_language), byLanguage.map { (lang, s) -> "${stringResource(lang.labelRes())} (${lang.shortLabel()})" to s }) }
            item { Bars(stringResource(R.string.stats_by_serie), bySerie) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.stats_runs), style = MaterialTheme.typography.titleMedium)
                        if (runs.isEmpty()) Text(stringResource(R.string.stats_no_runs), style = MaterialTheme.typography.bodySmall)
                        runs.forEach { run ->
                            Text(Format.dateTime(run.startedAt), style = MaterialTheme.typography.labelMedium)
                            Text(runSummaryText(run), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Row of the collection value for a source, with how many owned variants have a price.
 * @param label String - source of the price.
 * @param value String - total value already formatted.
 * @param priced Int - owned variants with a price from this source (included in the value).
 * @param owned Int - owned variants in total.
 * @return Unit
 */
@Composable
private fun ValueRow(label: String, value: String, priced: Int, owned: Int) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
        }
        Text(
            stringResource(R.string.stats_priced, priced, owned),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tile with a summary number.
 * @param label String - label.
 * @param value String - value.
 * @param hint String - note below the value.
 * @param modifier Modifier - layout modifier.
 * @return Unit
 */
@Composable
private fun StatTile(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Completion bars per group.
 * @param title String - title of the section.
 * @param groups List<Pair<String, StatsSummary>> - groups with their statistics.
 * @return Unit
 */
@Composable
private fun Bars(title: String, groups: List<Pair<String, StatsSummary>>) {
    if (groups.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            groups.forEach { (label, s) ->
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text("${s.owned}/${s.slots}", style = MonoStyle)
                    }
                    LinearProgressIndicator(
                        progress = { if (s.slots == 0) 0f else s.owned.toFloat() / s.slots },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
