@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.price.PriceAlertRules
import com.robertobiggi.cardcollection.work.Notifications
import com.robertobiggi.cardcollection.data.settings.PriceKind
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.Section
import kotlin.math.roundToInt

/** Lingue dell'interfaccia: [tag] vuoto significa "lingua di sistema". */
private enum class AppLanguage(val tag: String, val labelRes: Int) {
    SYSTEM("", R.string.app_lang_system),
    ITALIAN("it", R.string.app_lang_it),
    ENGLISH("en", R.string.app_lang_en),
}

/**
 * Impostazioni: lingua dell'app, catalogo, avvisi di prezzo, fonti dei prezzi e informazioni sui file JSON.
 * La lingua viene applicata subito da Android (l'activity si ricrea) e ricordata tra un avvio e l'altro.
 * @param vm MainViewModel - stato.
 * @param onBack () -> Unit - torna all'elenco collezioni.
 * @return Unit
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val catalogInfo by vm.catalogInfo.collectAsStateWithLifecycle()
    val savedUrl by vm.catalogUrl.collectAsStateWithLifecycle()
    val download by vm.catalogDownload.collectAsStateWithLifecycle()
    var urlInput by remember(savedUrl) { mutableStateOf(savedUrl) }
    val lastSync by vm.lastCatalogSync.collectAsStateWithLifecycle()
    val savedToken by vm.cardTraderToken.collectAsStateWithLifecycle()
    val visibility by vm.priceVisibility.collectAsStateWithLifecycle()
    val countFirstEdition by vm.countFirstEdition.collectAsStateWithLifecycle()
    var tokenInput by remember(savedToken) { mutableStateOf(savedToken) }
    val alertsEnabled by vm.priceAlertsEnabled.collectAsStateWithLifecycle()
    val alertPercent by vm.priceAlertChangePercent.collectAsStateWithLifecycle()
    var percentDraft by remember(alertPercent) { mutableFloatStateOf(alertPercent.toFloat()) }
    val context = LocalContext.current
    // Il risultato non serve: se l'utente rifiuta, la sezione mostra che le notifiche sono bloccate.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    val current = AppLanguage.entries.firstOrNull { it.tag == currentTag } ?: AppLanguage.SYSTEM

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            },
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section(stringResource(R.string.settings_app_language)) {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = lang == current,
                                role = Role.RadioButton,
                                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang.tag)) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lang == current, onClick = null)
                            Text(stringResource(lang.labelRes))
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.settings_catalog)) {
                    Text(stringResource(R.string.settings_catalog_text), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        catalogInfo?.let { stringResource(R.string.settings_catalog_installed, it.generatedAt.substringBefore('T'), it.languages.sorted().joinToString(", ")) }
                            ?: stringResource(R.string.settings_catalog_missing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it.take(300) },
                        label = { Text(stringResource(R.string.settings_catalog_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.downloadCatalog(urlInput) }, enabled = download == null) {
                            Text(stringResource(if (catalogInfo == null) R.string.settings_catalog_download else R.string.settings_catalog_update))
                        }
                        download?.let { d ->
                            Text(
                                if (d.total != null) "${d.done * 100 / d.total} %" else "${d.done / (1024 * 1024)} MB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.settings_first_edition)) {
                    PriceSwitch(stringResource(R.string.settings_first_edition), stringResource(R.string.settings_first_edition_text), countFirstEdition) {
                        vm.setCountFirstEdition(it)
                    }
                }
            }
            item {
                Section(stringResource(R.string.settings_alerts)) {
                    PriceSwitch(stringResource(R.string.settings_alerts_switch), stringResource(R.string.settings_alerts_text), alertsEnabled) { on ->
                        vm.setPriceAlertsEnabled(on)
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canNotify(context)) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (alertsEnabled) {
                        Text(stringResource(R.string.settings_alerts_percent, alertPercent), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = percentDraft,
                            onValueChange = { percentDraft = it },
                            onValueChangeFinished = { vm.setPriceAlertChangePercent(percentDraft.roundToInt()) },
                            valueRange = PriceAlertRules.CHANGE_PERCENT_RANGE.first.toFloat()..PriceAlertRules.CHANGE_PERCENT_RANGE.last.toFloat(),
                            steps = PriceAlertRules.CHANGE_PERCENT_RANGE.last - PriceAlertRules.CHANGE_PERCENT_RANGE.first - 1,
                        )
                        if (!Notifications.canNotify(context)) {
                            Text(stringResource(R.string.settings_alerts_no_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.settings_sources)) {
                    Text(stringResource(R.string.settings_sources_text), style = MaterialTheme.typography.bodyMedium)
                    PriceSwitch(stringResource(R.string.price_cardmarket), stringResource(R.string.source_cardmarket_text), visibility.cardmarket) {
                        vm.setPriceShown(PriceKind.CARDMARKET, it)
                    }
                    PriceSwitch(stringResource(R.string.price_tcgplayer), stringResource(R.string.source_tcgplayer_text), visibility.tcgplayer) {
                        vm.setPriceShown(PriceKind.TCGPLAYER, it)
                    }
                    PriceSwitch(stringResource(R.string.settings_cardtrader), stringResource(R.string.source_cardtrader_text), visibility.cardTrader) {
                        vm.setPriceShown(PriceKind.CARDTRADER, it)
                    }
                    Text(
                        if (lastSync > 0) stringResource(R.string.settings_last_catalog, Format.dateTime(lastSync)) else stringResource(R.string.settings_never_catalog),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Section(stringResource(R.string.settings_cardtrader)) {
                    Text(stringResource(R.string.settings_cardtrader_text), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text(stringResource(R.string.settings_cardtrader_token)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.saveCardTraderToken(tokenInput) }, enabled = tokenInput.trim() != savedToken) {
                            Text(stringResource(R.string.settings_cardtrader_save))
                        }
                        if (savedToken.isNotEmpty()) {
                            Text(stringResource(R.string.settings_cardtrader_active), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.settings_collections)) {
                    Text(stringResource(R.string.settings_collections_text), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_v1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Riga con nome, descrizione e interruttore di un tipo di prezzo.
 * @param title String - nome del prezzo.
 * @param description String - da dove arriva.
 * @param checked Boolean - prezzo mostrato.
 * @param onChange (Boolean) -> Unit - nuovo stato scelto.
 * @return Unit
 */
@Composable
private fun PriceSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
