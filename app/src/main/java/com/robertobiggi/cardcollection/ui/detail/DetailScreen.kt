@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.detail

import com.robertobiggi.cardcollection.core.model.VariantType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil3.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import com.robertobiggi.cardcollection.ui.common.AppSwitch
import com.robertobiggi.cardcollection.ui.common.stampName
import com.robertobiggi.cardcollection.data.repo.VariantImage
import androidx.compose.ui.res.pluralStringResource
import com.robertobiggi.cardcollection.core.backup.CollectionFiles
import com.robertobiggi.cardcollection.ui.common.imageSourceLabel
import com.robertobiggi.cardcollection.ui.common.labelRes
import com.robertobiggi.cardcollection.ui.common.variantLabel
import com.robertobiggi.cardcollection.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import com.robertobiggi.cardcollection.core.price.PriceAlertRules
import com.robertobiggi.cardcollection.data.local.PriceAlertEntity
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import com.robertobiggi.cardcollection.ui.CardAction
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.cardmarket.CardmarketLinks
import com.robertobiggi.cardcollection.core.cardtrader.CardTraderLinks
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.local.PriceSnapshotEntity
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.VariantUi
import com.robertobiggi.cardcollection.ui.CardActionReport
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.CardImage
import com.robertobiggi.cardcollection.ui.common.ConfidenceChip
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.MetaRow
import com.robertobiggi.cardcollection.ui.common.Sparkline
import com.robertobiggi.cardcollection.ui.theme.MonoStyle
import com.robertobiggi.cardcollection.ui.theme.StatusColors

/** A price older than this is flagged as "old". */
private const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Detail of a card: image, data, variants with quantity, condition and prices.
 * @param vm MainViewModel - state and actions.
 * @param cardId String - local id of the card.
 * @param onBack () -> Unit - goes back.
 * @return Unit
 */
@Composable
fun DetailScreen(vm: MainViewModel, cardId: String, onBack: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val card = library?.firstOrNull { it.card.id == cardId }
    val action by vm.cardAction.collectAsStateWithLifecycle()
    val report by vm.cardReport.collectAsStateWithLifecycle()
    val cardTraderToken by vm.cardTraderToken.collectAsStateWithLifecycle()
    val visibility by vm.priceVisibility.collectAsStateWithLifecycle()
    var manualFor by remember { mutableStateOf<VariantUi?>(null) }
    var others by remember(cardId) { mutableStateOf<List<VariantImage>>(emptyList()) }
    var showOthers by remember { mutableStateOf(false) }
    LaunchedEffect(cardId) { others = vm.variantImages(cardId) }
    var setNameEn by remember(cardId) { mutableStateOf<String?>(null) }
    var promoEvent by remember(cardId) { mutableStateOf<String?>(null) }
    LaunchedEffect(cardId) { promoEvent = vm.promoEvent(cardId) }
    LaunchedEffect(cardId) { card?.let { setNameEn = vm.setNameEn(it.language, it.card.setId) } }
    val alerts by vm.priceAlerts.collectAsStateWithLifecycle()
    val alertsEnabled by vm.priceAlertsEnabled.collectAsStateWithLifecycle()
    val alertPercent by vm.priceAlertChangePercent.collectAsStateWithLifecycle()
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
            item { Header(card, setNameEn, promoEvent, printEntries(card, others).size) { showOthers = true } }
            item {
                CardActions(
                    action = action,
                    onPrice = { vm.checkCardPrice(card.card.id) },
                    onDownload = { vm.downloadCardImage(card.card.id) },
                    onPick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
            item { Links(card, showCardTrader = visibility.cardTrader && cardTraderToken.isNotEmpty()) }
            item { Text(stringResource(R.string.variants_title), style = MaterialTheme.typography.titleMedium) }
            items(card.variants, key = { it.id }) { variant ->
                VariantCard(
                    card = card,
                    variant = variant,
                    onQuantityChange = { vm.changeQuantity(variant.id, it) },
                    onCondition = { vm.setCondition(variant.id, it) },
                    onWanted = { vm.setWanted(variant.id, it) },
                    onManual = { manualFor = variant },
                    alert = alerts[variant.id],
                    alertsEnabled = alertsEnabled,
                    defaultPercent = alertPercent,
                    onSaveAlert = { changes, percent, target -> vm.savePriceAlert(variant.id, changes, percent, target) },
                    onDeleteAlert = { vm.deletePriceAlert(variant.id) },
                    showCardmarket = visibility.cardmarket,
                    showTcgplayer = visibility.tcgplayer,
                    showCardTrader = visibility.cardTrader && cardTraderToken.isNotEmpty(),
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

    report?.let { CardActionDialog(it, onDismiss = vm::dismissCardReport) }

    if (showOthers && card != null) OtherPrintsDialog(vm, card, printEntries(card, others)) { showOthers = false }
    manualFor?.let { variant ->
        val owner = card ?: return@let
        ManualPriceDialog(
            variantLabel = variantLabel(owner, variant),
            onDismiss = { manualFor = null },
            onSave = { text -> vm.addManualPrice(variant.id, text).also { saved -> if (saved) manualFor = null } },
        )
    }
}

/**
 * Dialog of an action on the card: an indicator while it is in progress, then the outcome with the button to close it.
 * @param report CardActionReport - action and possible outcome.
 * @param onDismiss () -> Unit - closes the dialog (only when the outcome is available).
 * @return Unit
 */
@Composable
private fun CardActionDialog(report: CardActionReport, onDismiss: () -> Unit) {
    val message = report.message
    AlertDialog(
        onDismissRequest = { if (message != null) onDismiss() },
        title = {
            Text(
                stringResource(
                    when (report.action) {
                        CardAction.PRICE -> R.string.card_dialog_price
                        CardAction.DOWNLOAD_IMAGE -> R.string.card_dialog_download_image
                        CardAction.PICK_IMAGE -> R.string.card_dialog_pick_image
                    },
                ),
            )
        },
        text = {
            if (message == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.card_dialog_running))
                }
            } else {
                Text(message.resolve(LocalContext.current))
            }
        },
        confirmButton = { if (message != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

/**
 * Large image and metadata of the card.
 * @param card CardUi - card.
 * @param setNameEn String? - English name of the set (Japanese sets), null if none.
 * @param promoEvent String? - occasion of a promo without a number, null if unknown.
 * @return Unit
 */
@Composable
private fun Header(card: CardUi, setNameEn: String?, promoEvent: String?, otherPrints: Int, onOtherPrints: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val imagePath = card.card.imageHighPath ?: card.card.imageLowPath
        Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The base card's image is always the one shown; tapping it (when the database has images of other prints) lists them with their prices.
            CardImage(card, imagePath, Modifier.fillMaxWidth().then(if (otherPrints > 0) Modifier.clickable(onClick = onOtherPrints) else Modifier))
            imageSourceLabel(imagePath)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (otherPrints > 0) {
                Text(
                    pluralStringResource(R.plurals.other_prints_hint, otherPrints, otherPrints),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOtherPrints),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            MetaRow(stringResource(R.string.meta_code), card.printedCode, mono = true)
            MetaRow(stringResource(R.string.meta_set), card.card.setName)
            setNameEn?.let { MetaRow(stringResource(R.string.meta_set_en), it) }
            card.card.serieName?.let { MetaRow(stringResource(R.string.meta_serie), it) }
            card.card.rarity?.let { MetaRow(stringResource(R.string.meta_rarity), it) }
            MetaRow(stringResource(R.string.meta_language), stringResource(card.language.labelRes()))
            card.card.releaseDate?.let { MetaRow(stringResource(R.string.meta_release), it) }
            promoEvent?.let { MetaRow(stringResource(R.string.meta_event), it) }
            card.card.illustrator?.let { MetaRow(stringResource(R.string.meta_illustrator), it) }
        }
    }
}

/**
 * Actions on a single card: update the price, download the official image or pick one.
 * @param action CardAction? - action in progress, null if none.
 * @param onPrice () -> Unit - updates the card's prices.
 * @param onDownload () -> Unit - downloads the image from TCGdex.
 * @param onPick () -> Unit - opens the image picker.
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
 * Button of an action on the card, with an indicator while it is in progress.
 * @param label String - text of the button.
 * @param icon ImageVector - icon.
 * @param running Boolean - true if the action is in progress.
 * @param enabled Boolean - false while another action is in progress.
 * @param onClick () -> Unit - starts the action.
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
 * Links to the searches on Cardmarket, TCGplayer and CardTrader (opened in the browser, no calls from the app).
 * @param card CardUi - card.
 * @param showCardTrader Boolean - true if CardTrader prices are on.
 * @return Unit
 */
@Composable
private fun Links(card: CardUi, showCardTrader: Boolean) {
    val uriHandler = LocalUriHandler.current
    val term = Uri.encode(card.card.name)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { uriHandler.openSafely(CardmarketLinks.search(card.card.cardmarketName ?: card.card.name, card.card.cardmarketExpansionId)) }) {
            Text(stringResource(R.string.link_cardmarket))
        }
        if (card.language == Language.EN) {
            OutlinedButton(onClick = { uriHandler.openSafely("https://www.tcgplayer.com/search/pokemon/product?q=$term") }) {
                Text(stringResource(R.string.link_tcgplayer))
            }
        }
        if (showCardTrader) {
            val blueprintId = card.card.cardTraderBlueprintId
            val url = if (blueprintId != null) CardTraderLinks.card(blueprintId, card.card.name) else CardTraderLinks.search(term)
            OutlinedButton(onClick = { uriHandler.openSafely(url) }) {
                Text(stringResource(R.string.link_cardtrader))
            }
        }
    }
}

/**
 * Opens a link in the browser, without closing the app if the phone has no browser.
 * @receiver UriHandler - Compose link handler.
 * @param url String - address to open (built by the app, https only).
 * @return Unit
 */
private fun UriHandler.openSafely(url: String) {
    try {
        openUri(url)
    } catch (e: ActivityNotFoundException) {
        // No app able to open the link: nothing to do.
    } catch (e: IllegalArgumentException) {
        // This is how Compose signals links that no app handles on some versions.
    }
}

/**
 * Tile of a variant: slot, quantity, condition and prices.
 * @param card CardUi - card it belongs to.
 * @param variant VariantUi - variant.
 * @param onQuantityChange (Int) -> Unit - copies to add or remove (+1 / -1).
 * @param onCondition (Condition) -> Unit - new condition.
 * @param onWanted (Boolean) -> Unit - new "I want it" state.
 * @param onManual () -> Unit - opens the manual price entry.
 * @param alert PriceAlertEntity? - price alert of the variant, null if none.
 * @param alertsEnabled Boolean - true if the daily alert check is on in the settings.
 * @param defaultPercent Int - minimum change proposed for new alerts, from the settings.
 * @param onSaveAlert (Boolean, Int, String) -> Boolean - saves the alert (rises or falls, threshold, written target price); false if the price is not valid.
 * @param onDeleteAlert () -> Unit - removes the alert.
 * @param showCardmarket Boolean - false if the user hid the Cardmarket prices.
 * @param showTcgplayer Boolean - false if the user hid the TCGplayer prices.
 * @param showCardTrader Boolean - true if there is a CardTrader token and the price is not hidden: shows the tile next to the other markets.
 * @return Unit
 */
@Composable
private fun VariantCard(
    card: CardUi,
    variant: VariantUi,
    onQuantityChange: (Int) -> Unit,
    onCondition: (Condition) -> Unit,
    onWanted: (Boolean) -> Unit,
    onManual: () -> Unit,
    alert: PriceAlertEntity?,
    alertsEnabled: Boolean,
    defaultPercent: Int,
    onSaveAlert: (Boolean, Int, String) -> Boolean,
    onDeleteAlert: () -> Unit,
    showCardmarket: Boolean,
    showTcgplayer: Boolean,
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
                Text(variantLabel(card, variant), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Checkbox(checked = variant.wanted, onCheckedChange = onWanted)
                Text(stringResource(R.string.want_it), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { onQuantityChange(-1) }, enabled = variant.quantity > 0) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.qty_remove))
                }
                Text("${variant.quantity}", style = MaterialTheme.typography.titleMedium)
                FilledTonalIconButton(onClick = { onQuantityChange(1) }) {
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
                        else -> pluralStringResource(R.plurals.state_duplicates, variant.quantity - 1, variant.quantity - 1)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showCardmarket) {
                    PriceBox(stringResource(R.string.price_cardmarket), variant.latestEur?.eur?.let { Format.eur(it) }, Modifier.weight(1f))
                }
                if (showTcgplayer) {
                    PriceBox(
                        stringResource(R.string.price_tcgplayer),
                        variant.latestUsd?.usd?.let { Format.usd(it) },
                        Modifier.weight(1f),
                        emptyText = stringResource(if (card.language == Language.EN || card.language == Language.JA) R.string.no_price else R.string.not_sold),
                    )
                }
                if (showCardTrader) {
                    PriceBox(stringResource(R.string.price_cardtrader), variant.latestCardTrader?.eur?.let { Format.eur(it) }, Modifier.weight(1f))
                }
            }
            val last = variant.lastSnapshot
            if (showCardmarket) {
                SourceStatusRow(
                    last?.let { stringResource(R.string.price_source_date, Format.date(it.fetchedAt), sourceLabel(it.source)) } ?: stringResource(R.string.no_price_hint),
                    last, variant.eurHistory,
                )
            }
            if (showTcgplayer) {
                variant.latestUsd?.let { SourceStatusRow(stringResource(R.string.price_source_date, Format.date(it.fetchedAt), "TCGplayer"), it, variant.usdHistory) }
            }
            if (showCardTrader) {
                variant.latestCardTrader?.let { SourceStatusRow(stringResource(R.string.price_source_date, Format.date(it.fetchedAt), "CardTrader"), it, variant.cardTraderHistory) }
            }
            // Price alert right below the charts: change threshold and target price of this variant.
            PriceAlertPanel(alert, alertsEnabled, defaultPercent, onSaveAlert, onDeleteAlert)
            TextButton(onClick = onManual) { Text(stringResource(R.string.manual_price_button)) }
        }
    }
}

/**
 * Row with date and source of the latest price, reliability and trend.
 * @param text String - date and source already formatted.
 * @param snapshot PriceSnapshotEntity? - latest price of the source, null if missing.
 * @param history List<Double> - prices of the source from the oldest.
 * @return Unit
 */
@Composable
private fun SourceStatusRow(text: String, snapshot: PriceSnapshotEntity?, history: List<Double>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        // The chart's place stays reserved even when it is missing, so the label is always right-aligned.
        Box(Modifier.width(72.dp).height(22.dp)) { Sparkline(history, Modifier.fillMaxSize()) }
        if (snapshot != null) ConfidenceChip(snapshot.confidence, System.currentTimeMillis() - snapshot.fetchedAt > STALE_AFTER_MS)
    }
}

/**
 * Tile with a price.
 * @param label String - name of the market.
 * @param value String? - formatted price, null if absent.
 * @param modifier Modifier - layout modifier.
 * @param emptyText String? - text when the price is missing (null = "no price").
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
 * Dialog to enter a manual price in euros.
 * @param variantLabel String - name of the variant.
 * @param onDismiss () -> Unit - closes without saving.
 * @param onSave (String) -> Boolean - tries to save the text; returns false if not valid.
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
 * Price alert panel of a variant, below the price charts. Closed it shows a summary (threshold and target
 * price); open it lets the user choose whether to alert when the price rises or falls, by how much (this variant's threshold) and the
 * target price in euros.
 * @param alert PriceAlertEntity? - alert already set, null if none.
 * @param alertsEnabled Boolean - true if the daily check is on in the settings.
 * @param defaultPercent Int - threshold proposed for a new alert (or one saved before the choice was possible).
 * @param onSave (Boolean, Int, String) -> Boolean - saves (rises or falls, threshold, written target price); false if the price is not valid.
 * @param onDelete () -> Unit - removes the alert.
 * @return Unit
 */
@Composable
private fun PriceAlertPanel(
    alert: PriceAlertEntity?,
    alertsEnabled: Boolean,
    defaultPercent: Int,
    onSave: (Boolean, Int, String) -> Boolean,
    onDelete: () -> Unit,
) {
    // Survives rotation and other activity recreations, like the scroll position of the list.
    var open by rememberSaveable { mutableStateOf(false) }
    // The fields restart from what is saved every time the alert changes (saved, removed or moved by the sync).
    var changes by remember(alert) { mutableStateOf(alert?.notifyChanges ?: true) }
    var percent by remember(alert, defaultPercent) { mutableIntStateOf(alert?.changePercent ?: defaultPercent) }
    var target by remember(alert) { mutableStateOf(alert?.targetEur?.let { String.format(Locale.getDefault(), "%.2f", it) }.orEmpty()) }
    var error by remember(alert) { mutableStateOf(false) }
    val range = PriceAlertRules.CHANGE_PERCENT_RANGE
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (alert != null) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
                    contentDescription = null,
                    tint = if (alert != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(stringResource(R.string.alert_button), style = MaterialTheme.typography.labelLarge)
                    Text(alertSummary(alert, defaultPercent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (!open) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alert_changes_switch), modifier = Modifier.weight(1f))
                AppSwitch(checked = changes, onCheckedChange = { changes = it })
            }
            if (changes) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.alert_percent_label), modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { percent = (percent - 1).coerceIn(range) }, enabled = percent > range.first) {
                        Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.alert_percent_less))
                    }
                    Text(stringResource(R.string.alert_percent_value, percent), style = MaterialTheme.typography.titleMedium)
                    FilledTonalIconButton(onClick = { percent = (percent + 1).coerceIn(range) }, enabled = percent < range.last) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.alert_percent_more))
                    }
                }
            }
            OutlinedTextField(
                value = target,
                onValueChange = { target = it.take(10); error = false },
                label = { Text(stringResource(R.string.alert_target_label)) },
                isError = error,
                supportingText = {
                    Text(
                        if (error) stringResource(R.string.manual_price_error)
                        else stringResource(R.string.alert_target_hint, (PriceAlertRules.TARGET_MARGIN * 100).roundToInt()),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!alertsEnabled) {
                Text(stringResource(R.string.alert_disabled_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (onSave(changes, percent, target)) open = false else error = true }) { Text(stringResource(R.string.action_save)) }
                if (alert != null) {
                    TextButton(onClick = { onDelete(); open = false }) { Text(stringResource(R.string.alert_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/**
 * Summary of a price alert for the closed panel.
 * @param alert PriceAlertEntity? - alert, null if none.
 * @param defaultPercent Int - threshold used by alerts saved without a threshold of their own.
 * @return String - e.g. "±5% · below 50,00 €", or "No alert".
 */
@Composable
private fun alertSummary(alert: PriceAlertEntity?, defaultPercent: Int): String {
    if (alert == null) return stringResource(R.string.alert_none)
    val parts = listOfNotNull(
        if (alert.notifyChanges) stringResource(R.string.alert_summary_changes, alert.changePercent ?: defaultPercent) else null,
        alert.targetEur?.let { stringResource(R.string.alert_summary_target, Format.eur(it)) },
    )
    return parts.joinToString(" · ")
}

/**
 * Name of the source of a price: manual and imported prices have a translated label.
 * @param source String - source saved in the database.
 * @return String - name to show.
 */
@Composable
private fun sourceLabel(source: String): String = when (source) {
    CollectionFiles.MANUAL_SOURCE -> stringResource(R.string.source_manual)
    CollectionFiles.IMPORTED_SOURCE -> stringResource(R.string.source_imported)
    else -> source
}

/**
 * A print of a card shown in the dialog of the other prints.
 * @property type VariantType - type of the variant.
 * @property print String - stamp that tells it apart from the others of the same type.
 * @property url String? - address of its image, null if no source has one (a placeholder is shown).
 */
private data class PrintEntry(val type: VariantType, val print: String, val url: String?)

/**
 * The other prints of a card: those with an image in the card database, then the stamped variants of the card that have none.
 * @param card CardUi - card.
 * @param images List<VariantImage> - prints with an image.
 * @return List<PrintEntry> - what the dialog lists.
 */
private fun printEntries(card: CardUi, images: List<VariantImage>): List<PrintEntry> {
    val withImage = images.map { PrintEntry(it.type, it.print, it.url) }
    val without = card.variants
        .filter { v -> v.type == VariantType.STAMPED && withImage.none { it.type == v.type && it.print == v.print } }
        .map { PrintEntry(it.type, it.print, null) }
    return withImage + without
}

/**
 * Other prints of a card (e.g. with a stamp) with their image (or a placeholder) and prices; the image is downloaded only to be shown.
 * @param vm MainViewModel - downloads the images.
 * @param card CardUi - card, for the variants and their prices.
 * @param entries List<PrintEntry> - prints to list.
 * @param onDismiss () -> Unit - closes the dialog.
 * @return Unit
 */
@Composable
private fun OtherPrintsDialog(vm: MainViewModel, card: CardUi, entries: List<PrintEntry>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.other_prints_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.forEach { entry ->
                    val variant = card.variants.firstOrNull { it.type == entry.type && it.print == entry.print }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrintImage(vm, entry.url, card.card.name)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(variant?.let { variantLabel(card, it) } ?: stampName(entry.print), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.price_cardmarket) + ": " + Format.eur(variant?.latestEur?.eur),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            variant?.latestCardTrader?.eur?.let {
                                Text(stringResource(R.string.price_cardtrader) + ": " + Format.eur(it), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

/**
 * Image of a print, downloaded when shown; a placeholder if the print has none.
 * @param vm MainViewModel - downloads the image.
 * @param url String? - address of the image, null if there is none.
 * @param description String - description for accessibility.
 * @return Unit
 */
@Composable
private fun PrintImage(vm: MainViewModel, url: String?, description: String) {
    var bytes by remember(url) { mutableStateOf<ByteArray?>(null) }
    var done by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        bytes = url?.let { vm.previewImage(it) }
        done = true
    }
    Box(Modifier.width(96.dp).aspectRatio(63f / 88f), contentAlignment = Alignment.Center) {
        val data = bytes
        when {
            url == null -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.other_prints_no_image), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            data != null -> AsyncImage(model = data, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            !done -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
