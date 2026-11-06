package com.robertobiggi.cardcollection.ui.common

import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.runtime.mutableIntStateOf
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

/** Year digits in an ISO date ("2003-07-18"). */
private const val YEAR_LENGTH = 4

/** Languages offered for set collections: the ones with complete sets in the card database. */
val SET_LANGUAGES = listOf(Language.EN, Language.JA, Language.ZH_TW, Language.ZH_CN)

/** Order of the set list in the picker dialog. */
private enum class SetSort(val label: Int) {
    NEWEST(R.string.set_sort_newest),
    OLDEST(R.string.set_sort_oldest),
    NAME(R.string.set_sort_name),
}

/**
 * Sorts the sets: by release date (sets without a date at the end, by name when equal) or by name.
 * @param sets List<SetChoice> - sets to sort.
 * @param sort SetSort - wanted order.
 * @return List<SetChoice> - sorted sets.
 */
private fun sortSets(sets: List<SetChoice>, sort: SetSort): List<SetChoice> {
    val byName = compareBy<SetChoice, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    return when (sort) {
        SetSort.NAME -> sets.sortedWith(byName)
        SetSort.NEWEST -> sets.sortedWith(compareBy<SetChoice> { it.releaseDate == null }.thenByDescending { it.releaseDate }.then(byName))
        SetSort.OLDEST -> sets.sortedWith(compareBy<SetChoice> { it.releaseDate == null }.thenBy { it.releaseDate }.then(byName))
    }
}

/**
 * Dialog to choose a set: language (EN/JP), search by name or code and the list of sets.
 * @param state SetPickerState - list of sets and loading state.
 * @param onLanguage (Language, Boolean) -> Unit - asks for the list of a language; the second parameter forces a new download.
 * @param onPick (Language, SetChoice) -> Unit - chosen set.
 * @param onDismiss () -> Unit - closes without choosing.
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
    var sortIndex by rememberSaveable { mutableIntStateOf(SetSort.NEWEST.ordinal) }
    val sort = SetSort.entries[sortIndex]
    LaunchedEffect(Unit) { onLanguage(state.language, false) }
    val needle = PokemonNames.normalize(query)
    val shown = remember(state.sets, needle, sort) {
        val found = if (needle.isEmpty()) state.sets
        else state.sets.filter { PokemonNames.normalize(it.name).contains(needle) || it.nameEn?.let { n -> PokemonNames.normalize(n).contains(needle) } == true || PokemonNames.normalize(it.id).contains(needle) || it.code?.let { c -> PokemonNames.normalize(c).contains(needle) } == true }
        sortSets(found, sort)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SetSort.entries.forEach { option ->
                        FilterChip(selected = sort == option, onClick = { sortIndex = option.ordinal }, label = { Text(stringResource(option.label)) })
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
                                set.nameEn?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                val details = buildList {
                                    add(set.code ?: set.id)
                                    set.releaseDate?.take(YEAR_LENGTH)?.let { add(it) }
                                    set.cards?.let { add(pluralStringResource(R.plurals.set_pick_cards, it, it)) }
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
 * Choice of the level of a set collection: base set (one card per slot) or master set (all variants).
 * @param selected QueryKind - chosen level.
 * @param onSelect (QueryKind) -> Unit - new choice.
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
 * Short description of a search: the searched name or the set with its level.
 * @param kind QueryKind - type of search.
 * @param pattern String - name or id of the set.
 * @return String - text to show in the list of searches.
 */
@Composable
fun queryLabel(kind: QueryKind, pattern: String): String = when (kind) {
    QueryKind.NAME -> pattern
    QueryKind.SET_BASE -> stringResource(R.string.query_set_label, pattern, stringResource(R.string.set_level_base))
    QueryKind.SET_MASTER -> stringResource(R.string.query_set_label, pattern, stringResource(R.string.set_level_master))
}
