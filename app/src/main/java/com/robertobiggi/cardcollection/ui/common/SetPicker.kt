package com.robertobiggi.cardcollection.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.model.QueryKind
import com.robertobiggi.cardcollection.data.repo.SetChoice
import com.robertobiggi.cardcollection.ui.SetPickerState
import com.robertobiggi.cardcollection.ui.theme.MonoStyle

/** Lingue proposte per le collezioni per set: le uniche con set completi su TCGdex o nell'elenco dei set mancanti. */
val SET_LANGUAGES = listOf(Language.EN, Language.JA)

/**
 * Finestra di scelta di un set: lingua (EN/JP), ricerca per nome, id TCGdex, sigla stampata o Cardmarket (es. "swsh10", "ASR" o "astr") ed elenco dei set.
 * @param state SetPickerState - elenco dei set e stato del caricamento.
 * @param onLanguage (Language, Boolean) -> Unit - chiede l'elenco di una lingua; il secondo parametro forza il nuovo download.
 * @param onPick (Language, SetChoice) -> Unit - set scelto.
 * @param onDismiss () -> Unit - chiude senza scegliere.
 * @return Unit
 */
@Composable
fun SetPickerDialog(
    state: SetPickerState,
    onLanguage: (Language, Boolean) -> Unit,
    onPick: (Language, SetChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { onLanguage(state.language, false) }
    val needle = PokemonNames.normalize(query)
    val shown = remember(state.sets, needle) {
        if (needle.isEmpty()) state.sets
        else state.sets.filter { set ->
            (listOf(set.name, set.id) + set.codes).any { PokemonNames.normalize(it).contains(needle) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SET_LANGUAGES.forEach { lang ->
                        FilterChip(selected = state.language == lang, onClick = { onLanguage(lang, false) }, label = { Text(lang.shortLabel()) })
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(40) },
                    label = { Text(stringResource(R.string.set_pick_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    state.noCatalog -> Text(stringResource(R.string.msg_no_catalog), style = MaterialTheme.typography.bodySmall)
                    shown.isEmpty() -> Text(stringResource(R.string.set_pick_none), style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.id }) { set ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(state.language, set) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(set.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val details = buildList {
                                    add(set.id)
                                    if (set.codes.isNotEmpty()) add(set.codes.joinToString("/") { it.uppercase() })
                                    set.cards?.let { add(stringResource(R.string.set_pick_cards, it)) }
                                }
                                Text(details.joinToString(" · "), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Scelta del livello di una collezione per set: set base (una carta per slot) o master set (tutte le varianti).
 * @param selected QueryKind - livello scelto.
 * @param onSelect (QueryKind) -> Unit - nuova scelta.
 * @return Unit
 */
@Composable
fun SetLevelPicker(selected: QueryKind, onSelect: (QueryKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == QueryKind.SET_BASE, onClick = { onSelect(QueryKind.SET_BASE) }, label = { Text(stringResource(R.string.set_level_base)) })
            FilterChip(selected = selected == QueryKind.SET_MASTER, onClick = { onSelect(QueryKind.SET_MASTER) }, label = { Text(stringResource(R.string.set_level_master)) })
        }
        Text(
            stringResource(if (selected == QueryKind.SET_BASE) R.string.set_level_base_hint else R.string.set_level_master_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Descrizione breve di una ricerca: il nome cercato oppure il set con il livello.
 * @param kind QueryKind - tipo di ricerca.
 * @param pattern String - nome o id del set.
 * @return String - testo da mostrare nell'elenco delle ricerche.
 */
@Composable
fun queryLabel(kind: QueryKind, pattern: String): String = when (kind) {
    QueryKind.NAME -> pattern
    QueryKind.SET_BASE -> stringResource(R.string.query_set_label, pattern, stringResource(R.string.set_level_base))
    QueryKind.SET_MASTER -> stringResource(R.string.query_set_label, pattern, stringResource(R.string.set_level_master))
}
