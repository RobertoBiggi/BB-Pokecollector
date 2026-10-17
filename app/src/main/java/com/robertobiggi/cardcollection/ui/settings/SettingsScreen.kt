@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.Format

/**
 * Impostazioni: ricerche tracciate, stato delle fonti e backup.
 * @param vm MainViewModel - stato e azioni.
 * @return Unit
 */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val queries by vm.queries.collectAsStateWithLifecycle()
    val pausedUntil by vm.tcgdexPausedUntil.collectAsStateWithLifecycle()
    val lastSync by vm.lastCatalogSync.collectAsStateWithLifecycle()
    val catalogJob by vm.catalogJob.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(Language.EN) }
    var pattern by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Impostazioni") })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section("Carte tracciate") {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = language == Language.EN, onClick = { language = Language.EN }, label = { Text("EN") })
                        FilterChip(selected = language == Language.JA, onClick = { language = Language.JA }, label = { Text("JP") })
                    }
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it.take(40); error = null },
                        label = { Text("Nome Pokémon, es. Sableye") },
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        vm.addQuery(language, pattern) { err ->
                            error = err
                            if (err == null) pattern = ""
                        }
                    }) { Text("Aggiungi ricerca") }
                    Text(
                        "Ogni ricerca prende tutte le carte Pokémon che contengono quel nome (es. \"Mawile\" trova anche " +
                            "Mawile GX e Mega Mawile ex). Le carte di TCG Pocket e gli Allenatori sono esclusi. " +
                            "Disattivare una ricerca nasconde le sue carte ma non cancella le quantità.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = vm::startCatalogSync, enabled = catalogJob?.running != true) {
                        Text(if (catalogJob?.running == true) "Aggiornamento in corso…" else "Aggiorna catalogo")
                    }
                    Text(
                        if (lastSync > 0) "Ultimo catalogo scaricato il ${Format.dateTime(lastSync)}" else "Catalogo mai scaricato",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    catalogJob?.finishedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Section("Fonti prezzi") {
                    Text(
                        "I prezzi arrivano da TCGdex, che riporta Cardmarket (EUR, anche per le carte giapponesi) e " +
                            "TCGplayer (USD). L'app non contatta mai cardmarket.com: niente blocchi anti-bot.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (pausedUntil > System.currentTimeMillis()) {
                            "TCGdex in pausa fino al ${Format.dateTime(pausedUntil)} dopo troppi blocchi."
                        } else {
                            "TCGdex disponibile. Se blocca 3 volte di fila, l'app lo mette in pausa per 6 ore."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Quando nessuna fonte ha il prezzo, resta l'ultimo salvato e puoi inserirlo a mano dal dettaglio carta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Section("Backup") {
                    Text(
                        "Salva in un file JSON quantità, condizioni, varianti desiderate, prezzi manuali e ricerche. " +
                            "Il catalogo e le immagini non servono: si riscaricano.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportLauncher.launch("card-collection-backup.json") }) { Text("Esporta") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("Importa") }
                    }
                }
            }
        }
    }
}

/**
 * Sezione con titolo dentro una card.
 * @param title String - titolo.
 * @param content @Composable () -> Unit - contenuto della sezione.
 * @return Unit
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
