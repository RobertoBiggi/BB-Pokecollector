@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.missing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.stats.CollectionStats
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.VariantUi
import com.robertobiggi.cardcollection.data.repo.label
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.CardImage
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.theme.MonoStyle

/**
 * Elenco delle varianti desiderate che non possiedi, con il costo stimato.
 * @param vm MainViewModel - stato.
 * @param onOpen (String) -> Unit - apre il dettaglio della carta.
 * @return Unit
 */
@Composable
fun MissingScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val name by vm.collectionName.collectAsStateWithLifecycle()
    val cards = library.orEmpty()
    val missing = remember(cards) {
        cards.sortedWith(compareBy({ it.card.releaseDate ?: "9999" }, { it.card.localId.padStart(4, '0') }))
            .flatMap { c -> c.variants.filter { it.wanted && it.quantity == 0 }.map { c to it } }
    }
    val stats = remember(cards) { CollectionStats.compute(cards.flatMap { c -> c.variants.map { it.toSlotInput() } }) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Mancanti · ${name ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Ti mancano", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${stats.missing} varianti", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text(
                            buildString {
                                append("Costo stimato ${Format.eur(stats.missingEur)} (Cardmarket trend)")
                                if (stats.missingWithoutPrice > 0) append(" · ${stats.missingWithoutPrice} senza prezzo")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (missing.isEmpty()) {
                item {
                    Text(
                        if (cards.isEmpty()) "Scarica prima il catalogo dalla schermata Collezione." else "Collezione completa!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            missing.forEachIndexed { index, (card, variant) ->
                                if (index > 0) HorizontalDivider()
                                MissingRow(card, variant, onClick = { onOpen(card.card.id) })
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Le varianti segnate come \"non la voglio\" non compaiono qui e non contano nel completamento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Riga di una variante mancante.
 * @param card CardUi - carta.
 * @param variant VariantUi - variante mancante.
 * @param onClick () -> Unit - apre il dettaglio.
 * @return Unit
 */
@Composable
private fun MissingRow(card: CardUi, variant: VariantUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardImage(card, card.thumbnailPath, Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(card.card.name, fontWeight = FontWeight.Bold)
            Text(
                "${card.printedCode} · ${variant.type.label()} · ${if (card.language == Language.JA) "JP" else "EN"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(variant.latestEur?.eur?.let { Format.eur(it) } ?: "—", style = MonoStyle)
    }
}
