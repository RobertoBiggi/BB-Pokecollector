@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.detail

import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.ui.common.labelRes
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.ui.settings.conditionLabel
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import com.robertobiggi.cardcollection.ui.CardAction
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
    val action by vm.cardAction.collectAsStateWithLifecycle()
    val cardTraderToken by vm.cardTraderToken.collectAsStateWithLifecycle()
    val defaultCtCondition by vm.cardTraderCondition.collectAsStateWithLifecycle()
    var manualFor by remember { mutableStateOf<VariantUi?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setCardImage(cardId, uri)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(card?.card?.name ?: stringResource(R.string.card_default_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            },
        )
        if (card == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.card_not_found)) }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Header(card) }
            item {
                CardActions(
                    action = action,
                    onPrice = { vm.checkCardPrice(card.card.id) },
                    onDownload = { vm.downloadCardImage(card.card.id) },
                    onPick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
            if (cardTraderToken.isNotEmpty()) {
                item {
                    CardTraderCondition(
                        selected = card.card.cardTraderCondition?.let { CtCondition.fromName(it) },
                        default = defaultCtCondition,
                        onSelect = { vm.saveCardTraderCondition(card.card.id, it) },
                    )
                }
            }
            item { Links(card) }
            item { Text(stringResource(R.string.variants_title), style = MaterialTheme.typography.titleMedium) }
            items(card.variants, key = { it.id }) { variant ->
                VariantCard(
                    card = card,
                    variant = variant,
                    onQuantity = { vm.setQuantity(variant.id, it) },
                    onCondition = { vm.setCondition(variant.id, it) },
                    onWanted = { vm.setWanted(variant.id, it) },
                    onManual = { manualFor = variant },
                    showCardTrader = cardTraderToken.isNotEmpty(),
                )
            }
            item {
                TextButton(onClick = { vm.hideCard(card.card.id); onBack() }) {
                    Text(stringResource(R.string.remove_from_collection), color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Text(
                    stringResource(R.string.variants_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    manualFor?.let { variant ->
        ManualPriceDialog(
            variantLabel = stringResource(variant.type.labelRes()),
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
            MetaRow(stringResource(R.string.meta_code), card.printedCode, mono = true)
            MetaRow(stringResource(R.string.meta_set), card.card.setName)
            card.card.serieName?.let { MetaRow(stringResource(R.string.meta_serie), it) }
            card.card.rarity?.let { MetaRow(stringResource(R.string.meta_rarity), it) }
            MetaRow(stringResource(R.string.meta_language), stringResource(card.language.labelRes()))
            card.card.releaseDate?.let { MetaRow(stringResource(R.string.meta_release), it) }
            card.card.illustrator?.let { MetaRow(stringResource(R.string.meta_illustrator), it) }
        }
    }
}

/**
 * Azioni sulla singola carta: aggiorna il prezzo, scarica l'immagine ufficiale o scegline una.
 * @param action CardAction? - azione in corso, null se nessuna.
 * @param onPrice () -> Unit - aggiorna i prezzi della carta.
 * @param onDownload () -> Unit - scarica l'immagine da TCGdex.
 * @param onPick () -> Unit - apre il selettore di immagini.
 * @return Unit
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardActions(action: CardAction?, onPrice: () -> Unit, onDownload: () -> Unit, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.action_update_price), Icons.Filled.Refresh, action == CardAction.PRICE, action == null, onPrice)
            ActionButton(stringResource(R.string.action_download_image), Icons.Filled.Download, action == CardAction.DOWNLOAD_IMAGE, action == null, onDownload)
            ActionButton(stringResource(R.string.action_pick_image), Icons.Filled.Image, action == CardAction.PICK_IMAGE, action == null, onPick)
        }
        Text(
            stringResource(R.string.card_actions_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Pulsante di un'azione sulla carta, con indicatore mentre è in corso.
 * @param label String - testo del pulsante.
 * @param icon ImageVector - icona.
 * @param running Boolean - true se l'azione è in corso.
 * @param enabled Boolean - false mentre è in corso un'altra azione.
 * @param onClick () -> Unit - avvia l'azione.
 * @return Unit
 */
@Composable
private fun ActionButton(label: String, icon: ImageVector, running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        if (running) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(label)
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
            Text(stringResource(R.string.link_cardmarket))
        }
        if (card.language == Language.EN) {
            OutlinedButton(onClick = { uriHandler.openUri("https://www.tcgplayer.com/search/pokemon/product?q=$term") }) {
                Text(stringResource(R.string.link_tcgplayer))
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
 * @param showCardTrader Boolean - true se c'è il token CardTrader: mostra il riquadro accanto agli altri mercati.
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
    showCardTrader: Boolean,
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
                Text(stringResource(variant.type.labelRes()), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Checkbox(checked = variant.wanted, onCheckedChange = onWanted)
                Text(stringResource(R.string.want_it), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { onQuantity(variant.quantity - 1) }, enabled = variant.quantity > 0) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.qty_remove))
                }
                Text("${variant.quantity}", style = MaterialTheme.typography.titleMedium)
                FilledTonalIconButton(onClick = { onQuantity(variant.quantity + 1) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.qty_add))
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
                        variant.quantity == 0 && variant.wanted -> stringResource(R.string.state_missing)
                        variant.quantity == 0 -> stringResource(R.string.state_not_wanted)
                        variant.quantity == 1 -> stringResource(R.string.state_owned)
                        else -> stringResource(R.string.state_duplicates, variant.quantity - 1)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceBox(stringResource(R.string.price_cardmarket), variant.latestEur?.eur?.let { Format.eur(it) }, Modifier.weight(1f))
                PriceBox(
                    stringResource(R.string.price_tcgplayer),
                    variant.latestUsd?.usd?.let { Format.usd(it) },
                    Modifier.weight(1f),
                    emptyText = stringResource(if (card.language == Language.EN) R.string.no_price else R.string.not_sold),
                )
                if (showCardTrader) {
                    PriceBox(stringResource(R.string.price_cardtrader), variant.latestCardTrader?.eur?.let { Format.eur(it) }, Modifier.weight(1f))
                }
            }
            val last = variant.lastSnapshot
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    last?.let { stringResource(R.string.price_source_date, Format.date(it.fetchedAt), sourceLabel(it.source)) } ?: stringResource(R.string.no_price_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (last != null) ConfidenceChip(last.confidence, System.currentTimeMillis() - last.fetchedAt > STALE_AFTER_MS)
                Sparkline(variant.eurHistory, Modifier.width(72.dp).height(22.dp))
            }
            TextButton(onClick = onManual) { Text(stringResource(R.string.manual_price_button)) }
        }
    }
}

/**
 * Riquadro con un prezzo.
 * @param label String - nome del mercato.
 * @param value String? - prezzo formattato, null se assente.
 * @param modifier Modifier - modificatore di layout.
 * @param emptyText String? - testo quando il prezzo manca (null = "nessun prezzo").
 * @return Unit
 */
@Composable
private fun PriceBox(label: String, value: String?, modifier: Modifier = Modifier, emptyText: String? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: emptyText ?: stringResource(R.string.no_price), style = if (value != null) MonoStyle else MaterialTheme.typography.bodySmall)
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
        title = { Text(stringResource(R.string.manual_price_title, variantLabel)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(9); error = false },
                label = { Text(stringResource(R.string.manual_price_label)) },
                isError = error,
                supportingText = { if (error) Text(stringResource(R.string.manual_price_error)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { if (!onSave(text)) error = true }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Nome della fonte di un prezzo: i prezzi manuali hanno un'etichetta tradotta.
 * @param source String - fonte salvata nel database.
 * @return String - nome da mostrare.
 */
@Composable
private fun sourceLabel(source: String): String =
    if (source == CollectionFiles.MANUAL_SOURCE) stringResource(R.string.source_manual) else source

/**
 * Scelta della condizione peggiore accettata per il prezzo CardTrader di questa carta.
 * @param selected CtCondition? - scelta della carta, null se usa la predefinita.
 * @param default CtCondition - predefinita delle impostazioni.
 * @param onSelect (CtCondition?) -> Unit - nuova scelta (null = predefinita).
 * @return Unit
 */
@Composable
private fun CardTraderCondition(selected: CtCondition?, default: CtCondition, onSelect: (CtCondition?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ct_card_condition), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(
                    selected?.let { stringResource(conditionLabel(it)) }
                        ?: stringResource(R.string.ct_card_condition_default, stringResource(conditionLabel(default))),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ct_card_condition_default, stringResource(conditionLabel(default)))) },
                    onClick = { onSelect(null); open = false },
                )
                CtCondition.entries.forEach { c ->
                    DropdownMenuItem(text = { Text(stringResource(conditionLabel(c))) }, onClick = { onSelect(c); open = false })
                }
            }
        }
    }
}
