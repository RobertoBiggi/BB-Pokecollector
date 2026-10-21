@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.home

import com.robertobiggi.cardcollection.ui.common.exportFileName
import com.robertobiggi.cardcollection.ui.common.messageRes
import com.robertobiggi.cardcollection.ui.common.CollectionLanguagePicker
import com.robertobiggi.cardcollection.ui.common.LanguagePicker
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
import com.robertobiggi.cardcollection.data.repo.CollectionSummary
import com.robertobiggi.cardcollection.ui.MainViewModel
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
    val defaultImportName = stringResource(R.string.import_default_name)
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionSummary?>(null) }
    var renameError by remember { mutableStateOf<InputError?>(null) }
    var deleting by remember { mutableStateOf<CollectionSummary?>(null) }
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
                        onClick = { onOpen(summary.collection.id) },
                        onRename = { renameError = null; renaming = summary },
                        onExport = { exporting = summary },
                        onDelete = { deleting = summary },
                    )
                }
            }
        }
    }

    if (creating) {
        CreateCollectionDialog(
            onDismiss = { creating = false },
            onCreate = { name, collectionLanguage, queryLanguage, pattern, setError ->
                vm.createCollection(name, collectionLanguage, queryLanguage, pattern) { error, id ->
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
 * @param onClick () -> Unit - apre la collezione.
 * @param onRename () -> Unit - avvia la rinomina.
 * @param onExport () -> Unit - avvia l'export.
 * @param onDelete () -> Unit - avvia l'eliminazione.
 * @return Unit
 */
@Composable
private fun CollectionRow(summary: CollectionSummary, onClick: () -> Unit, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
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
                    Text(Format.eur(s.valueEur), style = MonoStyle)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_export_json)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/**
 * Finestra di creazione di una collezione: nome, lingua (EN, JP o mista) e una prima ricerca facoltativa.
 * La lingua della ricerca si sceglie solo per le collezioni miste.
 * @param onDismiss () -> Unit - chiude.
 * @param onCreate (String, Language?, Language, String, (Int) -> Unit) -> Unit - crea con nome, lingua
 *        della collezione (null = mista), lingua della prima ricerca e testo; l'ultimo parametro mostra
 *        un errore (risorsa stringa).
 * @return Unit
 */
@Composable
private fun CreateCollectionDialog(onDismiss: () -> Unit, onCreate: (String, Language?, Language, String, (Int) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var collectionLanguage by rememberSaveable { mutableStateOf<Language?>(Language.EN) }
    var language by rememberSaveable { mutableStateOf(Language.EN) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60); error = null },
                    label = { Text(stringResource(R.string.create_name_label)) },
                    singleLine = true,
                )
                CollectionLanguagePicker(collectionLanguage) { collectionLanguage = it }
                Text(stringResource(R.string.create_first_search), style = MaterialTheme.typography.bodySmall)
                if (collectionLanguage == null) LanguagePicker(language) { language = it }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it.take(40); error = null },
                    label = { Text(stringResource(R.string.create_pattern_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(stringResource(it)) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, collectionLanguage, language, pattern) { error = it } }) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
