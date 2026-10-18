@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.manage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.core.backup.ImageExport
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.ExportDialog
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.JobBanner
import com.robertobiggi.cardcollection.ui.settings.Section
import com.robertobiggi.cardcollection.ui.theme.MonoStyle

/**
 * Gestione della collezione aperta: ricerche, carte singole, carte tolte, catalogo ed export.
 * @param vm MainViewModel - stato e azioni.
 * @return Unit
 */
@Composable
fun ManageScreen(vm: MainViewModel) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val name by vm.collectionName.collectAsStateWithLifecycle()
    val queries by vm.queries.collectAsStateWithLifecycle()
    val hidden by vm.hiddenCards.collectAsStateWithLifecycle()
    val catalogJob by vm.catalogJob.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    var queryLanguage by rememberSaveable { mutableStateOf(Language.EN) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var queryError by remember { mutableStateOf<String?>(null) }
    var searchLanguage by rememberSaveable { mutableStateOf(Language.EN) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var exportOpen by remember { mutableStateOf(false) }
    var pendingImages by rememberSaveable { mutableStateOf(ImageExport.NONE.name) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = selected
        if (uri != null && id != null) vm.exportCollection(id, uri, ImageExport.valueOf(pendingImages))
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Gestione · ${name ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
        JobBanner("Catalogo", catalogJob)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section("Ricerche della collezione") {
                    if (queries.isEmpty()) Text("Nessuna ricerca: aggiungine una o inserisci carte singole qui sotto.", style = MaterialTheme.typography.bodySmall)
                    queries.forEach { q ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(q.pattern, fontWeight = FontWeight.Bold)
                                Text(if (q.language == Language.JA.code) "Giapponese" else "Inglese", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = q.enabled, onCheckedChange = { vm.setQueryEnabled(q.id, it) })
                            IconButton(onClick = { vm.deleteQuery(q.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Elimina ricerca") }
                        }
                    }
                    LanguageChips(queryLanguage) { queryLanguage = it }
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it.take(40); queryError = null },
                        label = { Text("Nome Pokémon, es. Sableye") },
                        isError = queryError != null,
                        supportingText = { queryError?.let { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        vm.addQuery(queryLanguage, pattern) { err ->
                            queryError = err
                            if (err == null) pattern = ""
                        }
                    }) { Text("Aggiungi ricerca") }
                    OutlinedButton(onClick = vm::startCatalogSync, enabled = catalogJob?.running != true) {
                        Text(if (catalogJob?.running == true) "Aggiornamento in corso…" else "Aggiorna catalogo")
                    }
                    catalogJob?.finishedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Una ricerca prende tutte le carte Pokémon che contengono quel nome; TCG Pocket e Allenatori sono esclusi. " +
                            "Disattivarla nasconde le sue carte senza cancellare le quantità.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Section("Aggiungi una carta singola") {
                    LanguageChips(searchLanguage) { searchLanguage = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it.take(40) },
                            label = { Text("Cerca su TCGdex") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { vm.searchCards(searchLanguage, searchText) }, enabled = !search.loading) { Text("Cerca") }
                    }
                    if (search.loading) CircularProgressIndicator()
                    search.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    search.results.forEach { card ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(card.name, fontWeight = FontWeight.Bold)
                                Text(card.id, style = MonoStyle)
                            }
                            IconButton(onClick = { vm.addSingleCard(card) }) { Icon(Icons.Filled.Add, contentDescription = "Aggiungi ${card.name}") }
                        }
                    }
                    Text(
                        "Utile per carte che nessuna ricerca trova (es. promo o carte con un nome diverso). Serve la connessione.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hidden.isNotEmpty()) {
                item {
                    Section("Carte tolte (${hidden.size})") {
                        hidden.forEach { card ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(card.name, fontWeight = FontWeight.Bold)
                                    Text(listOfNotNull(card.setCode, card.printedNumber).joinToString(" "), style = MonoStyle)
                                }
                                TextButton(onClick = { vm.restoreCard(card.id) }) { Text("Ripristina") }
                            }
                        }
                    }
                }
            }
            item {
                Section("Esporta") {
                    Text(
                        "Salva questa collezione in un file JSON con carte, quantità e prezzi, con o senza immagini. " +
                            "Si importa dall'elenco collezioni, anche su un altro telefono e senza rete.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { exportOpen = true }) { Text("Esporta JSON") }
                }
            }
        }
    }

    if (exportOpen) {
        ExportDialog(
            onDismiss = { exportOpen = false },
            onConfirm = { images ->
                pendingImages = images.name
                exportOpen = false
                val slug = (name ?: "collezione").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "collezione" }
                exportLauncher.launch("$slug-${Format.date(System.currentTimeMillis()).replace(' ', '-')}.json")
            },
        )
    }
}

/**
 * Scelta della lingua EN / JP.
 * @param selected Language - lingua selezionata.
 * @param onSelect (Language) -> Unit - nuova scelta.
 * @return Unit
 */
@Composable
private fun LanguageChips(selected: Language, onSelect: (Language) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == Language.EN, onClick = { onSelect(Language.EN) }, label = { Text("EN") })
        FilterChip(selected = selected == Language.JA, onClick = { onSelect(Language.JA) }, label = { Text("JP") })
    }
}
