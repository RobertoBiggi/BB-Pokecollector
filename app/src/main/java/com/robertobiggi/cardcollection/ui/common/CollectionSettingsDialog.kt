@file:OptIn(ExperimentalLayoutApi::class)

package com.robertobiggi.cardcollection.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.repo.CollectionSettings

/**
 * Impostazioni di una collezione: nome e lingue. Le lingue nuove copiano le ricerche per nome della collezione, quelle tolte
 * eliminano le loro ricerche (con una conferma, perché le carte trovate da quelle ricerche escono dalla collezione).
 * @param settings CollectionSettings - valori attuali.
 * @param error String? - errore da mostrare sotto il nome.
 * @param onDismiss () -> Unit - chiude senza salvare.
 * @param onSave (String, Set<Language>) -> Unit - salva nome e lingue scelte.
 * @return Unit
 */
@Composable
fun CollectionSettingsDialog(settings: CollectionSettings, error: String?, onDismiss: () -> Unit, onSave: (String, Set<Language>) -> Unit) {
    var name by rememberSaveable { mutableStateOf(settings.name) }
    var chosen by rememberSaveable { mutableStateOf(settings.languages.map { it.code }.toSet()) }
    var confirming by remember { mutableStateOf(false) }
    val languages = chosen.mapNotNull { Language.fromCode(it) }.toSet()
    val removed = Language.entries.filter { it in settings.languages && it !in languages }
    val added = Language.entries.filter { it in languages && it !in settings.languages }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collection_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.label_name)) },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    singleLine = true,
                )
                Text(stringResource(R.string.collection_settings_languages), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Language.entries.forEach { lang ->
                        val selected = lang in languages
                        FilterChip(
                            selected = selected,
                            // Si può togliere una lingua solo se ne resta un'altra, e metterne una nuova solo se c'è una ricerca per nome da copiare.
                            enabled = if (selected) languages.size > 1 else settings.canAddLanguage || lang in settings.languages,
                            onClick = { chosen = if (selected) chosen - lang.code else chosen + lang.code },
                            label = { Text(lang.shortLabel()) },
                        )
                    }
                }
                Text(
                    stringResource(if (settings.canAddLanguage) R.string.collection_settings_hint else R.string.collection_settings_no_names),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (added.isNotEmpty()) {
                    Text(stringResource(R.string.collection_settings_added, added.joinToString(", ") { it.shortLabel() }), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (removed.isEmpty()) onSave(name, languages) else confirming = true }, enabled = languages.isNotEmpty()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.collection_settings_remove_title),
            text = stringResource(R.string.collection_settings_remove_text, removed.joinToString(", ") { it.shortLabel() }),
            confirmLabel = stringResource(R.string.collection_settings_remove_confirm),
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onSave(name, languages)
            },
        )
    }
}
