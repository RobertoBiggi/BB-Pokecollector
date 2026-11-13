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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.backup.ImageExport

/**
 * Dialog to choose how many images to include in the exported file.
 * @param onDismiss () -> Unit - closes without exporting.
 * @param onConfirm (ImageExport) -> Unit - continues with the choice made.
 * @return Unit
 */
@Composable
fun ExportDialog(onDismiss: () -> Unit, onConfirm: (ImageExport) -> Unit) {
    var choice by remember { mutableStateOf(ImageExport.NONE) }
    val options = listOf(
        ImageExport.NONE to stringResource(R.string.export_none),
        ImageExport.LOW to stringResource(R.string.export_low),
        ImageExport.LOW_AND_HIGH to stringResource(R.string.export_high),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
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
        confirmButton = { TextButton(onClick = { onConfirm(choice) }) { Text(stringResource(R.string.action_choose_file)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Dialog with a text field (e.g. rename collection).
 * @param title String - title.
 * @param initial String - initial value.
 * @param label String - label of the field.
 * @param error String? - error to show.
 * @param onDismiss () -> Unit - closes without saving.
 * @param onConfirm (String) -> Unit - saves the text.
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
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Confirmation dialog for irreversible actions.
 * @param title String - title.
 * @param text String - explanation.
 * @param confirmLabel String - text of the confirm button.
 * @param onDismiss () -> Unit - cancels.
 * @param onConfirm () -> Unit - confirms.
 * @return Unit
 */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}


/**
 * Suggested file name for exporting a collection.
 * @param name String - name of the collection.
 * @return String - e.g. "mawile.json" (only letters, digits and hyphens).
 */
fun exportFileName(name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "bb-pokecollector" }
    return "$slug.json"
}
