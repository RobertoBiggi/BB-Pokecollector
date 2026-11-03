package com.robertobiggi.cardcollection.data.imagesource

import com.robertobiggi.cardcollection.core.image.CatalogImageSource
import com.robertobiggi.cardcollection.data.catalog.CatalogStore
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.CardEntity
import kotlinx.coroutines.CancellationException

/**
 * A site whose image paths are in the card database (one column per site, see [CatalogImageSource]). A card the database has no path for
 * costs no request ([SourceOutcome.NoLink]). Any new site of that kind needs only a [CatalogImageSource] in the core module.
 */
class CatalogColumnImageSource(
    private val source: CatalogImageSource,
    private val store: CatalogStore,
    private val images: ImageStore,
) : ImageSource {
    override val id = source.id
    private var known: Map<String, String> = emptyMap()

    override suspend fun prepare(cards: List<CardEntity>) {
        known = store.use { it.imagePaths(source, cards.map { c -> c.id }) }.orEmpty()
    }

    override suspend fun fetch(card: CardEntity): SourceOutcome {
        val path = known[card.id] ?: store.use { it.imagePaths(source, listOf(card.id)) }?.get(card.id) ?: return SourceOutcome.NoLink
        val url = source.urlOf(path) ?: return SourceOutcome.NoLink
        return try {
            val (low, high) = images.saveFromExternal(card.id, url, source.id, source::isAllowedUrl) ?: return SourceOutcome.Failed
            SourceOutcome.Saved(low, high)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceOutcome.Failed
        }
    }
}
