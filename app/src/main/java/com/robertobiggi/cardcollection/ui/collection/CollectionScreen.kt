@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.robertobiggi.cardcollection.ui.collection

import com.robertobiggi.cardcollection.ui.common.shortLabel
import com.robertobiggi.cardcollection.core.cardmarket.SetCodeAliases
import com.robertobiggi.cardcollection.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
 * Confronta la ricerca con il codice della carta scritto con l'id TCGdex o con la sigla Cardmarket del set,
 * es. "ex8 107" o "dx 107" per Rayquaza ☆ di EX Deoxys (codice stampato "107/107").
 * @param c CardUi - carta.
 * @param q String - ricerca già in minuscolo.
 * @return Boolean - true se la ricerca è contenuta in uno dei due codici.
 */
private fun matchesSetAlias(c: CardUi, q: String): Boolean {
    val number = c.card.printedNumber.ifEmpty { c.card.localId }
    if ("${c.card.setId} $number".lowercase().contains(q)) return true
    val code = SetCodeAliases.bundled.codeOf(c.language, c.card.setId, c.card.setName) ?: return false
    return "$code $number".contains(q)
}

/**
 * Applica ricerca, filtri e ordinamento alla libreria.
 * @param cards List<CardUi> - carte tracciate.
 * @param filter CollectionFilter - filtri correnti.
 * @return List<CardUi> - carte da mostrare.
 */
fun applyFilter(cards: List<CardUi>, filter: CollectionFilter): List<CardUi> {
    val q = filter.query.trim().lowercase()
    // Nome confrontato senza accenti e punteggiatura, anche tramite i nomi del Pokémon in altre lingue.
    val normalized = PokemonNames.normalize(q)
    val dexMatches = PokemonNames.dexIdsMatching(q)
    val filtered = cards.filter { c ->
        (filter.language == null || c.language == filter.language) &&
            (q.isEmpty() || c.card.name.lowercase().contains(q) ||
                (normalized.isNotEmpty() && PokemonNames.normalize(c.card.name).contains(normalized)) ||
                c.printedCode.lowercase().contains(q) || c.card.setName.lowercase().contains(q) ||
                (c.card.dexId != null && c.card.dexId in dexMatches) || (c.card.rarity?.lowercase()?.contains(q) == true) ||
                matchesSetAlias(c, q)) &&
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
 * Schermata principale: griglia delle carte con ricerca, filtri e pulsanti di aggiornamento.
 * @param vm MainViewModel - stato e azioni.
 * @param onOpen (String) -> Unit - apre il dettaglio della carta con l'id indicato.
 * @param onHome () -> Unit - torna all'elenco delle collezioni.
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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(name ?: stringResource(R.string.collection_default_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(lastUpdateText(runs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = {
                IconButton(onClick = onHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.all_collections)) }
            },
            actions = {
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
                // La ricerca resta fissa sopra l'elenco; i filtri scorrono con le carte.
                SearchField(filter, vm::updateFilter, Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Filters(filter, visible.size, cards.map { it.language }.distinct().sortedBy { it.ordinal }, vm::updateFilter)
                    }
                    items(visible, key = { it.card.id }) { card -> CardTile(card, onClick = { onOpen(card.card.id) }) }
                }
            }
        }
    }
}

/**
 * Testo con la data dell'ultimo aggiornamento prezzi concluso.
 * @param runs List<PriceRunEntity> - ultimi aggiornamenti.
 * @return String - frase da mostrare sotto il titolo.
 */
@Composable
private fun lastUpdateText(runs: List<PriceRunEntity>): String {
    val finished = runs.firstOrNull { it.finishedAt != null }?.finishedAt ?: return stringResource(R.string.prices_never)
    return stringResource(R.string.prices_updated_at, Format.dateTime(finished))
}

/**
 * Stato vuoto: invita a scaricare il catalogo.
 * @param onSync () -> Unit - avvia il sync.
 * @param syncing Boolean - true se il sync è già in corso.
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
 * Campo di ricerca per nome, sempre visibile sopra l'elenco.
 * @param filter CollectionFilter - filtri correnti.
 * @param onChange ((CollectionFilter) -> CollectionFilter) -> Unit - applica una modifica ai filtri.
 * @param modifier Modifier - posizione e margini.
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
 * Chip di filtro, ordinamento e numero di carte visibili.
 * @param filter CollectionFilter - filtri correnti.
 * @param count Int - carte visibili.
 * @param languages List<Language> - lingue presenti nella collezione.
 * @param onChange ((CollectionFilter) -> CollectionFilter) -> Unit - applica una modifica ai filtri.
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
            stringResource(R.string.cards_count_hint, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Riquadro di una carta nella griglia.
 * @param card CardUi - carta.
 * @param onClick () -> Unit - apre il dettaglio.
 * @return Unit
 */
@Composable
private fun CardTile(card: CardUi, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardImage(card, card.thumbnailPath, Modifier.fillMaxWidth())
            Text(card.card.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
