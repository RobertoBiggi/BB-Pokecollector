@file:OptIn(ExperimentalMaterial3Api::class)

package com.robertobiggi.cardcollection.ui.missing

import com.robertobiggi.cardcollection.ui.common.shortLabel
import com.robertobiggi.cardcollection.ui.common.variantLabel
import com.robertobiggi.cardcollection.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.robertobiggi.cardcollection.ui.CollectionFilter
import com.robertobiggi.cardcollection.ui.collection.applyFilter
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
import com.robertobiggi.cardcollection.data.repo.LibraryAssembler
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.VariantUi
import com.robertobiggi.cardcollection.ui.MainViewModel
import com.robertobiggi.cardcollection.ui.common.CardImage
import com.robertobiggi.cardcollection.ui.common.Format
import com.robertobiggi.cardcollection.ui.theme.MonoStyle

/**
 * List of the wanted variants you do not own, with the estimated cost.
 * @param vm MainViewModel - state.
 * @param onOpen (String) -> Unit - opens the card detail.
 * @return Unit
 */
@Composable
fun MissingScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val name by vm.collectionName.collectAsStateWithLifecycle()
    val cards = library.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    val missing = remember(cards, query) {
        // Same search as the collection (name, code, set, rarity, names in other languages); release order.
        applyFilter(cards, CollectionFilter(query = query.take(40)))
            .flatMap { c -> c.variants.filter { it.wanted && it.quantity == 0 }.map { c to it } }
    }
    val visibility by vm.priceVisibility.collectAsStateWithLifecycle()
    val stats = remember(cards) { LibraryAssembler.statsOf(cards) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.missing_title, name.orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis) })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.missing_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.missing_count, stats.missing), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        if (visibility.cardmarket) {
                            Text(stringResource(R.string.missing_cost, Format.eur(stats.missingEur)), style = MaterialTheme.typography.bodySmall)
                        }
                        if (visibility.cardmarket && stats.missingWithoutPrice > 0) {
                            Text(stringResource(R.string.missing_without_price, stats.missingWithoutPrice), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (missing.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            when {
                                cards.isEmpty() -> R.string.missing_download_first
                                query.isNotBlank() -> R.string.search_no_results
                                else -> R.string.missing_complete
                            },
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // One row per list item: with many missing, only the visible ones are drawn.
                itemsIndexed(missing, key = { _, row -> row.second.id }) { index, (card, variant) ->
                    Column {
                        if (index > 0) HorizontalDivider()
                        MissingRow(card, variant, visibility.cardmarket, onClick = { onOpen(card.card.id) })
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.missing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Row of a missing variant.
 * @param card CardUi - card.
 * @param variant VariantUi - missing variant.
 * @param showPrice Boolean - false if Cardmarket prices are hidden.
 * @param onClick () -> Unit - opens the detail.
 * @return Unit
 */
@Composable
private fun MissingRow(card: CardUi, variant: VariantUi, showPrice: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardImage(card, card.thumbnailPath, Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(card.card.name, fontWeight = FontWeight.Bold)
            Text(
                listOf(card.printedCode, variantLabel(card, variant), card.language.shortLabel()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showPrice) Text(variant.latestEur?.eur?.let { Format.eur(it) } ?: "—", style = MonoStyle)
    }
}
