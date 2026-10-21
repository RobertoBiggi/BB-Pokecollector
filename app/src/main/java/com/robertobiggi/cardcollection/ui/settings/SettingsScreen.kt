@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.cardtrader.CtCondition
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.common.Section

/** Lingue dell'interfaccia: [tag] vuoto significa "lingua di sistema". */
private enum class AppLanguage(val tag: String, val labelRes: Int) {
    SYSTEM("", R.string.app_lang_system),
    ITALIAN("it", R.string.app_lang_it),
    ENGLISH("en", R.string.app_lang_en),
}

/**
 * Impostazioni: lingua dell'app, fonti dei prezzi e informazioni sui file JSON.
 * La lingua viene applicata subito da Android (l'activity si ricrea) e ricordata tra un avvio e l'altro.
 * @param vm MainViewModel - stato.
 * @param onBack () -> Unit - torna all'elenco collezioni.
 * @return Unit
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val pausedUntil by vm.tcgdexPausedUntil.collectAsStateWithLifecycle()
    val lastSync by vm.lastCatalogSync.collectAsStateWithLifecycle()
    val savedToken by vm.cardTraderToken.collectAsStateWithLifecycle()
    val ctCondition by vm.cardTraderCondition.collectAsStateWithLifecycle()
    var tokenInput by remember(savedToken) { mutableStateOf(savedToken) }
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
                Section(stringResource(R.string.settings_sources)) {
                    Text(stringResource(R.string.settings_sources_text), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (pausedUntil > System.currentTimeMillis()) {
                            stringResource(R.string.settings_paused, Format.dateTime(pausedUntil))
                        } else {
                            stringResource(R.string.settings_available)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    Text(stringResource(R.string.settings_cardtrader_condition), style = MaterialTheme.typography.bodyMedium)
                    CtCondition.entries.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = c == ctCondition,
                                role = Role.RadioButton,
                                onClick = { vm.saveCardTraderCondition(c) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c == ctCondition, onClick = null)
                            Text(stringResource(conditionLabel(c)))
                        }
                    }
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

/** Etichetta della condizione peggiore accettata: "X o meglio", tranne l'ultima che accetta tutto. */
fun conditionLabel(c: CtCondition): Int = when (c) {
    CtCondition.MINT -> R.string.ct_cond_mint
    CtCondition.NEAR_MINT -> R.string.ct_cond_nm
    CtCondition.SLIGHTLY_PLAYED -> R.string.ct_cond_sp
    CtCondition.MODERATELY_PLAYED -> R.string.ct_cond_mp
    CtCondition.PLAYED -> R.string.ct_cond_played
    CtCondition.POOR -> R.string.ct_cond_any
}
