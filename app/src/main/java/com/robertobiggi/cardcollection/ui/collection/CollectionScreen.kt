@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.robertobiggi.cardcollection.ui.collection

import androidx.compose.ui.res.pluralStringResource
import com.robertobiggi.cardcollection.ui.common.shortLabel
import com.robertobiggi.cardcollection.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.ui.CollectionFilter
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.OwnedFilter
import com.robertobiggi.cardcollection.ui.SortMode
import com.robertobiggi.cardcollection.ui.common.CardImage
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.JobBanner
import com.robertobiggi.cardcollection.ui.common.SlotBadge
import com.robertobiggi.cardcollection.ui.theme.MonoStyle

/**
 * Applies search, filters and ordering to the library.
 * @param cards List<CardUi> - tracked cards.
 * @param filter CollectionFilter - current filters.
 * @return List<CardUi> - cards to show.
 */
fun applyFilter(cards: List<CardUi>, filter: CollectionFilter): List<CardUi> {
    val q = filter.query.trim().lowercase()
    // Name compared without accents and punctuation, also via the Pokémon names in other languages.
    val normalized = PokemonNames.normalize(q)
    val dexMatches = PokemonNames.dexIdsMatching(q)
    val filtered = cards.filter { c ->
        (filter.language == null || c.language == filter.language) &&
            (q.isEmpty() || c.card.name.lowercase().contains(q) ||
                (normalized.isNotEmpty() && PokemonNames.normalize(c.card.name).contains(normalized)) ||
                c.printedCode.lowercase().contains(q) || c.card.setName.lowercase().contains(q) ||
                (c.card.dexId != null && c.card.dexId in dexMatches) || (c.card.rarity?.lowercase()?.contains(q) == true)) &&
            when (filter.owned) {
                OwnedFilter.ALL -> true
                OwnedFilter.OWNED -> c.copies > 0
                OwnedFilter.MISSING -> c.ownedSlots < c.slots
            }
    }
    return when (filter.sort) {
        SortMode.RELEASE -> filtered.sortedWith(CardUi.RELEASE_ORDER)
        SortMode.PRICE -> filtered.sortedByDescending { it.minEur ?: -1.0 }
        SortMode.CODE -> filtered.sortedWith(compareBy<CardUi>({ it.card.setCode == null }, { it.card.setCode ?: it.card.setId }, { it.card.localId.padStart(4, '0') }))
        SortMode.NAME -> filtered.sortedBy { it.card.name.lowercase() }
    }
}

/**
 * Main screen: card grid with search, filters and update buttons.
 * @param vm MainViewModel - state and actions.
 * @param onOpen (String) -> Unit - opens the detail of the card with the given id.
 * @param onHome () -> Unit - returns to the list of collections.
 * @return Unit
 */
@Composable
fun CollectionScreen(vm: MainViewModel, onOpen: (String) -> Unit, onHome: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val catalogJob by vm.catalogJob.collectAsStateWithLifecycle()
    val priceJob by vm.priceJob.collectAsStateWithLifecycle()
    val name by vm.collectionName.collectAsStateWithLifecycle()
    val cards = library
    val visible by vm.visibleCards.collectAsStateWithLifecycle()
    val needsUpdate by vm.needsCatalogUpdate.collectAsStateWithLifecycle()
    // A collection that needs the update (new app or database) starts it by itself as soon as it opens.
    LaunchedEffect(needsUpdate) { if (needsUpdate) vm.updateIfNeeded() }
    // Cards a database update no longer lists, kept because they have user data: a separate section at the end.
    val removedCards = visible.filter { it.removedByUpdate }
    val shownCards = visible.filterNot { it.removedByUpdate }
    // Multiple selection: opens with a long press on a card and closes with the X or the back button.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(pluralStringResource(R.plurals.remove_selected_title, selected.size, selected.size)) },
            text = { Text(stringResource(R.string.remove_selected_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.hideCards(selected)
                    selected = emptySet()
                    confirmRemove = false
                }) { Text(stringResource(R.string.remove_selected_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (selecting) {
                    Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size))
                } else Column {
                    Text(name ?: stringResource(R.string.collection_default_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(lastUpdateText(runs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = {
                if (selecting) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
                } else IconButton(onClick = onHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.all_collections)) }
            },
            actions = {
                if (selecting) {
                    IconButton(onClick = { selected = visible.map { it.card.id }.toSet() }) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.select_all))
                    }
                    IconButton(onClick = { confirmRemove = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_from_collection), tint = MaterialTheme.colorScheme.error)
                    }
                    return@TopAppBar
                }
                IconButton(onClick = vm::startCatalogSync, enabled = catalogJob?.running != true) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.update_catalog))
                }
                FilledTonalButton(
                    onClick = vm::startPriceCheck,
                    enabled = priceJob?.running != true && !cards.isNullOrEmpty(),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text(stringResource(R.string.prices_button), modifier = Modifier.padding(start = 4.dp))
                }
            },
        )
        JobBanner(stringResource(R.string.job_catalog), catalogJob, prices = false)
        JobBanner(stringResource(R.string.job_prices), priceJob, prices = true)

        when {
            cards == null -> Unit
            cards.isEmpty() -> EmptyLibrary(onSync = vm::startCatalogSync, syncing = catalogJob?.running == true)
            else -> Column(Modifier.fillMaxSize()) {
                // The search stays fixed above the list; the filters scroll with the cards.
                SearchField(filter, vm::updateFilter, Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Filters(filter, shownCards.size, cards.map { it.language }.distinct().sortedBy { it.ordinal }, vm::updateFilter)
                    }
                    items(shownCards, key = { it.card.id }) { card ->
                        val id = card.card.id
                        CardTile(
                            card,
                            selected = id in selected,
                            onClick = { if (selecting) selected = if (id in selected) selected - id else selected + id else onOpen(id) },
                            onLongClick = { selected = if (id in selected) selected - id else selected + id },
                        )
                    }
                    if (removedCards.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { RemovedByUpdateHeader(removedCards.size) }
                        items(removedCards, key = { it.card.id }) { card ->
                            val id = card.card.id
                            val replacement = card.replacedBy?.let { rid -> cards.firstOrNull { it.card.id == rid }?.printedCode ?: rid }
                            CardTile(
                                card,
                                removedLabel = stringResource(if (replacement != null) R.string.removed_replaced_by else R.string.removed_gone, replacement.orEmpty()),
                                selected = id in selected,
                                onClick = { if (selecting) selected = if (id in selected) selected - id else selected + id else onOpen(id) },
                                onLongClick = { selected = if (id in selected) selected - id else selected + id },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Header of the section at the end of the collection with the cards a database update no longer lists: a divider, the title with the count and
 * the explanation of why they are there.
 * @param count Int - cards in the section.
 * @return Unit
 */
@Composable
private fun RemovedByUpdateHeader(count: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.removed_by_update_title) + " ($count)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.removed_by_update_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Text with the date of the last completed price update.
 * @param runs List<PriceRunEntity> - latest updates.
 * @return String - sentence to show below the title.
 */
@Composable
private fun lastUpdateText(runs: List<PriceRunEntity>): String {
    val finished = runs.firstOrNull { it.finishedAt != null }?.finishedAt ?: return stringResource(R.string.prices_never)
    return stringResource(R.string.prices_updated_at, Format.dateTime(finished))
}

/**
 * Empty state: invites to download the catalog.
 * @param onSync () -> Unit - starts the sync.
 * @param syncing Boolean - true if the sync is already running.
 * @return Unit
 */
@Composable
private fun EmptyLibrary(onSync: () -> Unit, syncing: Boolean) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.empty_collection_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.empty_collection_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSync, enabled = !syncing) {
                Text(stringResource(if (syncing) R.string.downloading else R.string.download_catalog))
            }
        }
    }
}

/**
 * Search-by-name field, always visible above the list.
 * @param filter CollectionFilter - current filters.
 * @param onChange ((CollectionFilter) -> CollectionFilter) -> Unit - applies a change to the filters.
 * @param modifier Modifier - position and margins.
 * @return Unit
 */
@Composable
private fun SearchField(filter: CollectionFilter, onChange: ((CollectionFilter) -> CollectionFilter) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = filter.query,
        onValueChange = { text -> onChange { it.copy(query = text) } },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Filter chips, ordering and number of visible cards.
 * @param filter CollectionFilter - current filters.
 * @param count Int - visible cards.
 * @param languages List<Language> - languages present in the collection.
 * @param onChange ((CollectionFilter) -> CollectionFilter) -> Unit - applies a change to the filters.
 * @return Unit
 */
@Composable
private fun Filters(filter: CollectionFilter, count: Int, languages: List<Language>, onChange: ((CollectionFilter) -> CollectionFilter) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwnedFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter.owned == f,
                    onClick = { onChange { it.copy(owned = f) } },
                    label = {
                        Text(
                            stringResource(
                                when (f) {
                                    OwnedFilter.ALL -> R.string.filter_all
                                    OwnedFilter.OWNED -> R.string.filter_owned
                                    OwnedFilter.MISSING -> R.string.filter_missing
                                },
                            ),
                        )
                    },
                )
            }
            if (languages.size > 1) {
                (listOf<Language?>(null) + languages).forEach { lang ->
                    FilterChip(
                        selected = filter.language == lang,
                        onClick = { onChange { it.copy(language = lang) } },
                        label = { Text(lang?.shortLabel() ?: stringResource(R.string.filter_all_languages)) },
                    )
                }
            }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text(stringResource(R.string.sort_label, stringResource(filter.sort.labelRes))) }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(stringResource(mode.labelRes)) }, onClick = {
                            onChange { it.copy(sort = mode) }
                            sortOpen = false
                        })
                    }
                }
            }
        }
        Text(
            pluralStringResource(R.plurals.cards_count_hint, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tile of a card in the grid.
 * @param card CardUi - card.
 * @param selected Boolean - true if the card is in the multiple selection.
 * @param onClick () -> Unit - opens the detail, or in selection adds or removes it.
 * @param onLongClick () -> Unit - starts the selection or adds or removes the card.
 * @param removedLabel String? - why the card is in the section of the cards a database update no longer lists, shown on the tile; null for the others.
 * @return Unit
 */
@Composable
private fun CardTile(card: CardUi, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, removedLabel: String? = null) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardImage(card, card.thumbnailPath, Modifier.fillMaxWidth())
            Text(card.card.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (removedLabel != null) Text(removedLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.printedCode, style = MonoStyle)
            Text(
                listOfNotNull(card.card.rarity, card.language.shortLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlotBadge(card.ownedSlots, card.slots)
                    if (card.copies > 0) Text(stringResource(R.string.copies_short, card.copies), style = MaterialTheme.typography.labelMedium)
                }
                Text(card.minEur?.let { stringResource(R.string.price_from, Format.eur(it)) } ?: "—", style = MonoStyle)
            }
        }
    }
}
