@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.detail

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.VariantUi
import com.robertobiggi.cardcollection.data.repo.label
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.CardImage
import com.robertobiggi.cardcollection.ui.common.ConfidenceChip
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.MetaRow
import com.robertobiggi.cardcollection.ui.common.Sparkline
import com.robertobiggi.cardcollection.ui.theme.MonoStyle
import com.robertobiggi.cardcollection.ui.theme.StatusColors

/** Un prezzo più vecchio di così viene segnalato come "vecchio". */
private const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Dettaglio di una carta: immagine, dati, varianti con quantità, condizione e prezzi.
 * @param vm MainViewModel - stato e azioni.
 * @param cardId String - id locale della carta.
 * @param onBack () -> Unit - torna indietro.
 * @return Unit
 */
@Composable
fun DetailScreen(vm: MainViewModel, cardId: String, onBack: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val card = library?.firstOrNull { it.card.id == cardId }
    var manualFor by remember { mutableStateOf<VariantUi?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(card?.card?.name ?: "Carta") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
            },
        )
        if (card == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Carta non trovata") }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Header(card) }
            item { Links(card) }
            item { Text("Varianti", style = MaterialTheme.typography.titleMedium) }
            items(card.variants, key = { it.id }) { variant ->
                VariantCard(
                    card = card,
                    variant = variant,
                    onQuantity = { vm.setQuantity(variant.id, it) },
                    onCondition = { vm.setCondition(variant.id, it) },
                    onWanted = { vm.setWanted(variant.id, it) },
                    onManual = { manualFor = variant },
                )
            }
            item {
                Text(
                    "Ogni variante è uno slot della collezione. Togli \"la voglio\" alle varianti che non ti interessano: " +
                        "non conteranno come mancanti.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    manualFor?.let { variant ->
        ManualPriceDialog(
            variantLabel = variant.type.label(),
            onDismiss = { manualFor = null },
            onSave = { text -> vm.addManualPrice(variant.id, text).also { saved -> if (saved) manualFor = null } },
        )
    }
}

/**
 * Immagine grande e metadati della carta.
 * @param card CardUi - carta.
 * @return Unit
 */
@Composable
private fun Header(card: CardUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CardImage(card, card.card.imageHighPath ?: card.card.imageLowPath, Modifier.width(160.dp))
        Column(Modifier.weight(1f)) {
            MetaRow("Codice", card.printedCode, mono = true)
            MetaRow("Set", card.card.setName)
            card.card.serieName?.let { MetaRow("Serie", it) }
            card.card.rarity?.let { MetaRow("Rarità", it) }
            MetaRow("Lingua", if (card.language == Language.JA) "Giapponese" else "Inglese")
            card.card.releaseDate?.let { MetaRow("Uscita", it) }
            card.card.illustrator?.let { MetaRow("Illustratore", it) }
        }
    }
}

/**
 * Collegamenti alle ricerche su Cardmarket e TCGplayer (aperti nel browser, nessuna chiamata dall'app).
 * @param card CardUi - carta.
 * @return Unit
 */
@Composable
private fun Links(card: CardUi) {
    val uriHandler = LocalUriHandler.current
    val term = Uri.encode(card.card.name)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { uriHandler.openUri("https://www.cardmarket.com/it/Pokemon/Products/Search?searchString=$term") }) {
            Text("Cardmarket ↗")
        }
        if (card.language == Language.EN) {
            OutlinedButton(onClick = { uriHandler.openUri("https://www.tcgplayer.com/search/pokemon/product?q=$term") }) {
                Text("TCGplayer ↗")
            }
        }
    }
}

/**
 * Riquadro di una variante: slot, quantità, condizione e prezzi.
 * @param card CardUi - carta di appartenenza.
 * @param variant VariantUi - variante.
 * @param onQuantity (Int) -> Unit - nuova quantità.
 * @param onCondition (Condition) -> Unit - nuova condizione.
 * @param onWanted (Boolean) -> Unit - nuovo stato "la voglio".
 * @param onManual () -> Unit - apre l'inserimento del prezzo manuale.
 * @return Unit
 */
@Composable
private fun VariantCard(
    card: CardUi,
    variant: VariantUi,
    onQuantity: (Int) -> Unit,
    onCondition: (Condition) -> Unit,
    onWanted: (Boolean) -> Unit,
    onManual: () -> Unit,
) {
    var conditionOpen by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (variant.quantity > 0) StatusColors.ok else MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.size(10.dp),
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(variant.type.label(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Checkbox(checked = variant.wanted, onCheckedChange = onWanted)
                Text("la voglio", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { onQuantity(variant.quantity - 1) }, enabled = variant.quantity > 0) {
                    Icon(Icons.Filled.Remove, contentDescription = "Togli una copia")
                }
                Text("${variant.quantity}", style = MaterialTheme.typography.titleMedium)
                FilledTonalIconButton(onClick = { onQuantity(variant.quantity + 1) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Aggiungi una copia")
                }
                Box {
                    TextButton(onClick = { conditionOpen = true }) { Text(variant.condition.name) }
                    DropdownMenu(expanded = conditionOpen, onDismissRequest = { conditionOpen = false }) {
                        Condition.entries.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = { onCondition(c); conditionOpen = false })
                        }
                    }
                }
                Text(
                    when {
                        variant.quantity == 0 && variant.wanted -> "Ti manca"
                        variant.quantity == 0 -> "Non la cerchi"
                        variant.quantity == 1 -> "Posseduta"
                        else -> "${variant.quantity - 1} doppioni"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceBox("Cardmarket · trend", variant.latestEur?.eur?.let { Format.eur(it) }, Modifier.weight(1f))
                PriceBox(
                    "TCGplayer · market",
                    variant.latestUsd?.usd?.let { Format.usd(it) },
                    Modifier.weight(1f),
                    emptyText = if (card.language == Language.JA) "non venduta" else "nessun prezzo",
                )
            }
            val last = variant.lastSnapshot
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    last?.let { "${Format.date(it.fetchedAt)} · ${it.source}" } ?: "Nessun prezzo: aggiorna o inseriscilo a mano",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (last != null) ConfidenceChip(last.confidence, System.currentTimeMillis() - last.fetchedAt > STALE_AFTER_MS)
                Sparkline(variant.eurHistory, Modifier.width(72.dp).height(22.dp))
            }
            TextButton(onClick = onManual) { Text("Inserisci prezzo manuale") }
        }
    }
}

/**
 * Riquadro con un prezzo.
 * @param label String - nome del mercato.
 * @param value String? - prezzo formattato, null se assente.
 * @param modifier Modifier - modificatore di layout.
 * @param emptyText String - testo quando il prezzo manca.
 * @return Unit
 */
@Composable
private fun PriceBox(label: String, value: String?, modifier: Modifier = Modifier, emptyText: String = "nessun prezzo") {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: emptyText, style = if (value != null) MonoStyle else MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Finestra per inserire un prezzo manuale in euro.
 * @param variantLabel String - nome della variante.
 * @param onDismiss () -> Unit - chiude senza salvare.
 * @param onSave (String) -> Boolean - prova a salvare il testo; restituisce false se non valido.
 * @return Unit
 */
@Composable
private fun ManualPriceDialog(variantLabel: String, onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prezzo manuale · $variantLabel") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(9); error = false },
                label = { Text("Prezzo in euro, es. 2,50") },
                isError = error,
                supportingText = { if (error) Text("Inserisci un importo valido (massimo 99999,99).") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { if (!onSave(text)) error = true }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
