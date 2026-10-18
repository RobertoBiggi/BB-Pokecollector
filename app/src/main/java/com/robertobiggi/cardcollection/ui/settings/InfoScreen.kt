@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.Format

/**
 * Informazioni generali: fonti dei prezzi, stato di TCGdex, backup.
 * @param vm MainViewModel - stato.
 * @param onBack () -> Unit - torna all'elenco collezioni.
 * @return Unit
 */
@Composable
fun InfoScreen(vm: MainViewModel, onBack: () -> Unit) {
    val pausedUntil by vm.tcgdexPausedUntil.collectAsStateWithLifecycle()
    val lastSync by vm.lastCatalogSync.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Informazioni") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") } },
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        if (lastSync > 0) "Ultimo catalogo scaricato il ${Format.dateTime(lastSync)}" else "Catalogo mai scaricato",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Section("Collezioni e file JSON") {
                    Text(
                        "Ogni collezione ha ricerche, carte e quantità proprie; catalogo, immagini e prezzi sono condivisi. " +
                            "Dal menu di una collezione puoi esportarla in un file JSON, con o senza immagini, e importarla " +
                            "su un altro telefono dall'icona in alto nell'elenco: l'import crea sempre una nuova collezione.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Sono accettati anche i backup della prima versione dell'app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
