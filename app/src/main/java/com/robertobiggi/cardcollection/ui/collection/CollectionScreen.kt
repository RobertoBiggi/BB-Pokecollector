@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.robertobiggi.cardcollection.ui.collection

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
 * Applica ricerca, filtri e ordinamento alla libreria.
 * @param cards List<CardUi> - carte tracciate.
 * @param filter CollectionFilter - filtri correnti.
 * @return List<CardUi> - carte da mostrare.
 */
fun applyFilter(cards: List<CardUi>, filter: CollectionFilter): List<CardUi> {
    val q = filter.query.trim().lowercase()
    val filtered = cards.filter { c ->
        (filter.language == null || c.language == filter.language) &&
            (q.isEmpty() || c.card.name.lowercase().contains(q) || c.printedCode.lowercase().contains(q) ||
                c.card.setName.lowercase().contains(q) || (c.card.rarity?.lowercase()?.contains(q) == true)) &&
            when (filter.owned) {
                OwnedFilter.ALL -> true
                OwnedFilter.OWNED -> c.copies > 0
                OwnedFilter.MISSING -> c.ownedSlots < c.slots
            }
    }
    return when (filter.sort) {
        SortMode.RELEASE -> filtered.sortedWith(compareBy<CardUi>({ it.card.releaseDate ?: "9999" }, { it.card.setId }, { it.card.localId.padStart(4, '0') }))
        SortMode.PRICE -> filtered.sortedByDescending { it.minEur ?: -1.0 }
        SortMode.CODE -> filtered.sortedWith(compareBy<CardUi>({ it.card.setCode == null }, { it.card.setCode ?: it.card.setId }, { it.card.localId.padStart(4, '0') }))
        SortMode.NAME -> filtered.sortedBy { it.card.name.lowercase() }
    }
}

/**
 * Schermata principale: griglia delle carte con ricerca, filtri e pulsanti di aggiornamento.
 * @param vm MainViewModel - stato e azioni.
 * @param onOpen (String) -> Unit - apre il dettaglio della carta con l'id indicato.
 * @return Unit
 */
@Composable
fun CollectionScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val catalogJob by vm.catalogJob.collectAsStateWithLifecycle()
    val priceJob by vm.priceJob.collectAsStateWithLifecycle()
    val cards = library
    val visible = remember(cards, filter) { applyFilter(cards.orEmpty(), filter) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Collezione")
                    Text(lastUpdateText(runs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = vm::startCatalogSync, enabled = catalogJob?.running != true) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = "Aggiorna catalogo")
                }
                FilledTonalButton(
                    onClick = vm::startPriceCheck,
                    enabled = priceJob?.running != true && !cards.isNullOrEmpty(),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("Prezzi", modifier = Modifier.padding(start = 4.dp))
                }
            },
        )
        JobBanner("Catalogo", catalogJob)
        JobBanner("Prezzi", priceJob)

        when {
            cards == null -> Unit
            cards.isEmpty() -> EmptyLibrary(onSync = vm::startCatalogSync, syncing = catalogJob?.running == true)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Filters(filter, visible.size, vm::updateFilter)
                }
                items(visible, key = { it.card.id }) { card -> CardTile(card, onClick = { onOpen(card.card.id) }) }
            }
        }
    }
}

/**
 * Testo con la data dell'ultimo aggiornamento prezzi concluso.
 * @param runs List<PriceRunEntity> - ultimi aggiornamenti.
 * @return String - frase da mostrare sotto il titolo.
 */
private fun lastUpdateText(runs: List<PriceRunEntity>): String {
    val last = runs.firstOrNull { it.finishedAt != null } ?: return "Prezzi mai aggiornati"
    return "Prezzi aggiornati il ${Format.dateTime(last.finishedAt ?: last.startedAt)}"
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
            Text("Nessuna carta salvata", style = MaterialTheme.typography.titleMedium)
            Text(
                "Scarica il catalogo delle ricerche attive (Mawile in inglese e クチート in giapponese). " +
                    "Carte e immagini restano sul telefono e sono consultabili anche offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSync, enabled = !syncing) { Text(if (syncing) "Scaricamento in corso…" else "Scarica catalogo") }
        }
    }
}

/**
 * Ricerca, chip di filtro e ordinamento.
 * @param filter CollectionFilter - filtri correnti.
 * @param count Int - carte visibili.
 * @param onChange ((CollectionFilter) -> CollectionFilter) -> Unit - applica una modifica ai filtri.
 * @return Unit
 */
@Composable
private fun Filters(filter: CollectionFilter, count: Int, onChange: ((CollectionFilter) -> CollectionFilter) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { text -> onChange { it.copy(query = text) } },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Nome, codice (es. M1L 046) o rarità") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwnedFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter.owned == f,
                    onClick = { onChange { it.copy(owned = f) } },
                    label = { Text(when (f) { OwnedFilter.ALL -> "Tutte"; OwnedFilter.OWNED -> "Possedute"; OwnedFilter.MISSING -> "Mancanti" }) },
                )
            }
            listOf(null, Language.EN, Language.JA).forEach { lang ->
                FilterChip(
                    selected = filter.language == lang,
                    onClick = { onChange { it.copy(language = lang) } },
                    label = { Text(lang?.let { if (it == Language.JA) "JP" else "EN" } ?: "EN + JP") },
                )
            }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text("Ordina: ${filter.sort.label}") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                            onChange { it.copy(sort = mode) }
                            sortOpen = false
                        })
                    }
                }
            }
        }
        Text(
            "$count carte · il badge indica varianti possedute su varianti che vuoi",
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
                listOfNotNull(card.card.rarity, if (card.language == Language.JA) "JP" else "EN").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SlotBadge(card.ownedSlots, card.slots)
                Text(card.minEur?.let { "da ${Format.eur(it)}" } ?: "—", style = MonoStyle)
            }
        }
    }
}
