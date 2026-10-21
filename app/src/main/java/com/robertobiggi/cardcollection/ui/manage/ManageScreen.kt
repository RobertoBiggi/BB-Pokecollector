@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.manage

import com.robertobiggi.cardcollection.ui.common.messageRes
import com.robertobiggi.cardcollection.ui.common.labelRes
import com.robertobiggi.cardcollection.ui.common.catalogResultText
import com.robertobiggi.cardcollection.ui.common.CollectionLanguagePicker
import com.robertobiggi.cardcollection.ui.common.LanguagePicker
import com.robertobiggi.cardcollection.data.repo.InputError
import com.robertobiggi.cardcollection.R
import androidx.compose.ui.res.stringResource
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
import com.robertobiggi.cardcollection.core.model.CollectionLanguage
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.ExportDialog
import com.robertobiggi.cardcollection.ui.common.exportFileName
import com.robertobiggi.cardcollection.ui.common.JobBanner
import com.robertobiggi.cardcollection.ui.common.Section
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
    val collectionLanguage by vm.collectionLanguage.collectAsStateWithLifecycle()
    // Lingua fissa della collezione: se c'è, ricerche e aggiunte la usano senza chiederla.
    val fixedLanguage = CollectionLanguage.fixedLanguage(collectionLanguage)
    val queries by vm.queries.collectAsStateWithLifecycle()
    val hidden by vm.hiddenCards.collectAsStateWithLifecycle()
    val catalogJob by vm.catalogJob.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    var queryLanguage by rememberSaveable { mutableStateOf(Language.EN) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var queryError by remember { mutableStateOf<InputError?>(null) }
    var searchLanguage by rememberSaveable { mutableStateOf(Language.EN) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var exportOpen by remember { mutableStateOf(false) }
    var pendingImages by rememberSaveable { mutableStateOf(ImageExport.NONE.name) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = selected
        if (uri != null && id != null) vm.exportCollection(id, uri, ImageExport.valueOf(pendingImages))
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.manage_title, name.orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis) })
        JobBanner(stringResource(R.string.job_catalog), catalogJob, prices = false)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Section(stringResource(R.string.manage_searches)) {
                    if (collectionLanguage != null) CollectionLanguagePicker(fixedLanguage) { vm.setCollectionLanguage(it) }
                    if (queries.isEmpty()) Text(stringResource(R.string.manage_no_searches), style = MaterialTheme.typography.bodySmall)
                    queries.forEach { q ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(q.pattern, fontWeight = FontWeight.Bold)
                                val lang = Language.fromCode(q.language)
                                Text(lang?.let { stringResource(it.labelRes()) } ?: q.language, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = q.enabled, onCheckedChange = { vm.setQueryEnabled(q.id, it) })
                            IconButton(onClick = { vm.deleteQuery(q.id) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_search)) }
                        }
                    }
                    if (fixedLanguage == null) LanguagePicker(queryLanguage) { queryLanguage = it }
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it.take(40); queryError = null },
                        label = { Text(stringResource(R.string.search_name_label)) },
                        isError = queryError != null,
                        supportingText = { queryError?.let { Text(stringResource(it.messageRes())) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        vm.addQuery(fixedLanguage ?: queryLanguage, pattern) { err ->
                            queryError = err
                            if (err == null) pattern = ""
                        }
                    }) { Text(stringResource(R.string.add_search)) }
                    OutlinedButton(onClick = vm::startCatalogSync, enabled = catalogJob?.running != true) {
                        Text(stringResource(if (catalogJob?.running == true) R.string.updating else R.string.update_catalog))
                    }
                    catalogResultText(catalogJob)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        stringResource(R.string.manage_searches_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Section(stringResource(R.string.add_single_title)) {
                    if (fixedLanguage == null) LanguagePicker(searchLanguage) { searchLanguage = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it.take(40) },
                            label = { Text(stringResource(R.string.search_tcgdex)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { vm.searchCards(fixedLanguage ?: searchLanguage, searchText) }, enabled = !search.loading) { Text(stringResource(R.string.action_search)) }
                    }
                    if (search.loading) CircularProgressIndicator()
                    search.messageRes?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    search.results.forEach { card ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(card.name, fontWeight = FontWeight.Bold)
                                Text(card.id, style = MonoStyle)
                            }
                            IconButton(onClick = { vm.addSingleCard(card) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_card_cd, card.name)) }
                        }
                    }
                    Text(
                        stringResource(R.string.add_single_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hidden.isNotEmpty()) {
                item {
                    Section(stringResource(R.string.removed_cards, hidden.size)) {
                        hidden.forEach { card ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(card.name, fontWeight = FontWeight.Bold)
                                    Text(listOfNotNull(card.setCode, card.printedNumber).joinToString(" "), style = MonoStyle)
                                }
                                TextButton(onClick = { vm.restoreCard(card.id) }) { Text(stringResource(R.string.action_restore)) }
                            }
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.export_section)) {
                    Text(stringResource(R.string.export_section_text), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { exportOpen = true }) { Text(stringResource(R.string.home_export_json)) }
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
                exportLauncher.launch(exportFileName(name.orEmpty()))
            },
        )
    }
}
