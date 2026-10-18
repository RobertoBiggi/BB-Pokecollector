package com.robertobiggi.cardcollection.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.robertobiggi.cardcollection.core.backup.ImageExport

/**
 * Finestra per scegliere quante immagini includere nel file esportato.
 * @param onDismiss () -> Unit - chiude senza esportare.
 * @param onConfirm (ImageExport) -> Unit - prosegue con la scelta fatta.
 * @return Unit
 */
@Composable
fun ExportDialog(onDismiss: () -> Unit, onConfirm: (ImageExport) -> Unit) {
    var choice by remember { mutableStateOf(ImageExport.NONE) }
    val options = listOf(
        ImageExport.NONE to "Senza immagini · file leggero, le immagini si riscaricano",
        ImageExport.LOW to "Con immagini piccole · consultabile subito offline",
        ImageExport.LOW_AND_HIGH to "Con immagini piccole e grandi · file più pesante",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Esporta collezione") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = choice == value, onClick = { choice = value }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == value, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text("Scegli file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/**
 * Finestra con un campo di testo (es. rinomina collezione).
 * @param title String - titolo.
 * @param initial String - valore iniziale.
 * @param label String - etichetta del campo.
 * @param error String? - errore da mostrare.
 * @param onDismiss () -> Unit - chiude senza salvare.
 * @param onConfirm (String) -> Unit - salva il testo.
 * @return Unit
 */
@Composable
fun TextInputDialog(title: String, initial: String, label: String, error: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                label = { Text(label) },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/**
 * Finestra di conferma per azioni irreversibili.
 * @param title String - titolo.
 * @param text String - spiegazione.
 * @param confirmLabel String - testo del pulsante di conferma.
 * @param onDismiss () -> Unit - annulla.
 * @param onConfirm () -> Unit - conferma.
 * @return Unit
 */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

