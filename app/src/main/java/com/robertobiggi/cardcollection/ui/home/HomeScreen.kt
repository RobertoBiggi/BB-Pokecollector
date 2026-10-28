@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.home

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import com.robertobiggi.cardcollection.ui.common.SetPickerDialog
import com.robertobiggi.cardcollection.ui.common.SetLevelPicker
import com.robertobiggi.cardcollection.ui.SetPickerState
import com.robertobiggi.cardcollection.data.repo.SetChoice
import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.ui.common.exportFileName
import com.robertobiggi.cardcollection.ui.common.messageRes
import com.robertobiggi.cardcollection.ui.common.LanguageMultiPicker
import com.robertobiggi.cardcollection.data.repo.InputError
import com.robertobiggi.cardcollection.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.robertobiggi.cardcollection.core.backup.ImageExport
import com.robertobiggi.cardcollection.core.model.CollectionLanguage
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.repo.CollectionSettings
import com.robertobiggi.cardcollection.data.repo.CollectionSummary
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.CollectionSettingsDialog
import com.robertobiggi.cardcollection.ui.common.ConfirmDialog
import com.robertobiggi.cardcollection.ui.common.ExportDialog
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.TextInputDialog
import com.robertobiggi.cardcollection.ui.common.shortLabel
import com.robertobiggi.cardcollection.ui.theme.MonoStyle
import java.io.File

/**
 * Elenco iniziale delle collezioni: apertura, creazione, rinomina, export, eliminazione e import.
 * @param vm MainViewModel - stato e azioni.
 * @param onOpen (Long) -> Unit - apre la collezione indicata.
 * @param onSettings () -> Unit - apre le impostazioni.
 * @return Unit
 */
@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val visibility by vm.priceVisibility.collectAsStateWithLifecycle()
    val defaultImportName = stringResource(R.string.import_default_name)
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionSummary?>(null) }
    var renameError by remember { mutableStateOf<InputError?>(null) }
    var deleting by remember { mutableStateOf<CollectionSummary?>(null) }
    var settingsOf by remember { mutableStateOf<Pair<Long, CollectionSettings>?>(null) }
    var settingsError by remember { mutableStateOf<InputError?>(null) }
    var exporting by remember { mutableStateOf<CollectionSummary?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<Pair<Long, String>?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCollection(uri, defaultImportName)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = pendingExport
        if (uri != null && pending != null) vm.exportCollection(pending.first, uri, ImageExport.valueOf(pending.second))
        pendingExport = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = stringResource(R.string.home_import))
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new)) },
            )
        },
    ) { padding ->
        val list = summaries
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.home_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(list, key = { it.collection.id }) { summary ->
                    CollectionRow(
                        summary = summary,
                        showEur = visibility.cardmarket,
                        onClick = { onOpen(summary.collection.id) },
                        onRename = { renameError = null; renaming = summary },
                        onSettings = { settingsError = null; vm.loadCollectionSettings(summary.collection.id) { loaded -> settingsOf = loaded?.let { summary.collection.id to it } } },
                        onExport = { exporting = summary },
                        onDelete = { deleting = summary },
                    )
                }
            }
        }
    }

    if (creating) {
        val setPicker by vm.setPicker.collectAsStateWithLifecycle()
        CreateCollectionDialog(
            setPicker = setPicker,
            onLoadSets = vm::loadSets,
            onDismiss = { creating = false },
            onCreate = { name, languages, pattern, setError ->
                vm.createCollection(name, languages, pattern) { error, id ->
                    if (error != null) setError(error.messageRes()) else {
                        creating = false
                        id?.let(onOpen)
                    }
                }
            },
            onCreateSet = { name, language, set, kind, setError ->
                vm.createSetCollection(name, language, set, kind) { error, id ->
                    if (error != null) setError(error.messageRes()) else {
                        creating = false
                        id?.let(onOpen)
                    }
                }
            },
        )
    }
    renaming?.let { target ->
        TextInputDialog(
            title = stringResource(R.string.rename_title),
            initial = target.collection.name,
            label = stringResource(R.string.label_name),
            error = renameError?.let { stringResource(it.messageRes()) },
            onDismiss = { renaming = null },
            onConfirm = { name -> vm.renameCollection(target.collection.id, name) { err -> if (err == null) renaming = null else renameError = err } },
        )
    }
    settingsOf?.let { (id, current) ->
        CollectionSettingsDialog(
            settings = current,
            error = settingsError?.let { stringResource(it.messageRes()) },
            onDismiss = { settingsOf = null },
            onSave = { name, languages -> vm.saveCollectionSettings(id, name, languages) { err -> if (err == null) settingsOf = null else settingsError = err } },
        )
    }
    deleting?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.delete_title, target.collection.name),
            text = stringResource(R.string.delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onDismiss = { deleting = null },
            onConfirm = {
                vm.deleteCollection(target.collection.id)
                deleting = null
            },
        )
    }
    exporting?.let { target ->
        ExportDialog(
            onDismiss = { exporting = null },
            onConfirm = { images ->
                pendingExport = target.collection.id to images.name
                exporting = null
                exportLauncher.launch(exportFileName(target.collection.name))
            },
        )
    }
}

/**
 * Riga di una collezione con copertina, completamento e valore.
 * @param summary CollectionSummary - dati della collezione.
 * @param showEur Boolean - false se i prezzi Cardmarket sono nascosti: il valore non viene mostrato.
 * @param onClick () -> Unit - apre la collezione.
 * @param onRename () -> Unit - avvia la rinomina.
 * @param onSettings () -> Unit - apre le impostazioni (nome e lingue).
 * @param onExport () -> Unit - avvia l'export.
 * @param onDelete () -> Unit - avvia l'eliminazione.
 * @return Unit
 */
@Composable
private fun CollectionRow(summary: CollectionSummary, showEur: Boolean, onClick: () -> Unit, onRename: () -> Unit, onSettings: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val s = summary.stats
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(64.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val cover = summary.coverPath
                // Se il file manca, Coil segnala l'errore e si torna all'iniziale (niente accessi al disco qui).
                var coverFailed by remember(cover) { mutableStateOf(false) }
                if (cover != null && !coverFailed) {
                    AsyncImage(
                        model = File(cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = { coverFailed = true },
                    )
                } else {
                    Text(summary.collection.name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        summary.collection.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        CollectionLanguage.fixedLanguage(summary.collection.language)?.shortLabel() ?: stringResource(R.string.lang_mixed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.home_cards_variants, summary.cardCount, s.owned, s.slots),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { s.completionPercent() / 100f }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.home_complete, s.completionPercent()), style = MaterialTheme.typography.labelMedium)
                    if (showEur) Text(Format.eur(s.valueEur), style = MonoStyle)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.collection_settings_title)) }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_export_json)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Lunghezza massima del testo con i nomi da cercare (fino a 20 nomi separati da virgole). */
private const val CREATE_PATTERN_MAX = 400

/**
 * Finestra di creazione di una collezione. Due modi:
 * - per Pokémon: nome, lingue (EN, JP o altre, anche insieme) e una prima ricerca facoltativa;
 * - per set: un set intero da scegliere nell'elenco, come set base o master set.
 * @param setPicker SetPickerState - elenco dei set per il modo "per set".
 * @param onLoadSets (Language, Boolean) -> Unit - chiede l'elenco dei set di una lingua.
 * @param onDismiss () -> Unit - chiude.
 * @param onCreate (String, List<Language>, String, (Int) -> Unit) -> Unit - crea per Pokémon; l'ultimo parametro mostra un errore (risorsa stringa).
 * @param onCreateSet (String, Language, SetChoice, QueryKind, (Int) -> Unit) -> Unit - crea per set; l'ultimo parametro mostra un errore.
 * @return Unit
 */
@Composable
private fun CreateCollectionDialog(
    setPicker: SetPickerState,
    onLoadSets: (Language, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, List<Language>, String, (Int) -> Unit) -> Unit,
    onCreateSet: (String, Language, SetChoice, QueryKind, (Int) -> Unit) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var languages by rememberSaveable { mutableStateOf(listOf(Language.EN)) }
    var bySet by rememberSaveable { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf(QueryKind.SET_MASTER) }
    var chosen by remember { mutableStateOf<Pair<Language, SetChoice>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !bySet, onClick = { bySet = false; error = null }, label = { Text(stringResource(R.string.create_by_name)) })
                    FilterChip(selected = bySet, onClick = { bySet = true; error = null }, label = { Text(stringResource(R.string.create_by_set)) })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60); error = null },
                    label = { Text(stringResource(if (bySet) R.string.create_name_optional else R.string.create_name_label)) },
                    singleLine = true,
                )
                if (bySet) {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(chosen?.let { (lang, set) -> "${set.name} (${lang.shortLabel()})" } ?: stringResource(R.string.set_pick_button), maxLines = 2)
                    }
                    SetLevelPicker(level) { level = it }
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(stringResource(R.string.create_first_search), style = MaterialTheme.typography.bodySmall)
                    LanguageMultiPicker(languages) { languages = it }
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it.take(CREATE_PATTERN_MAX); error = null },
                        label = { Text(stringResource(R.string.create_pattern_label)) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = { error?.let { Text(stringResource(it)) } },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = chosen
                when {
                    !bySet -> onCreate(name, languages, pattern) { error = it }
                    target == null -> error = R.string.set_pick_missing
                    else -> onCreateSet(name, target.first, target.second, level) { error = it }
                }
            }) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (picking) {
        SetPickerDialog(
            state = setPicker,
            onLanguage = onLoadSets,
            onPick = { lang, set -> chosen = lang to set; picking = false; error = null },
            onDismiss = { picking = false },
        )
    }
}
