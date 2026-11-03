package com.robertobiggi.cardcollection.data.imagesource

import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.AppDatabase
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.repo.ImageResult
import com.robertobiggi.cardcollection.data.repo.SyncPhase
import com.robertobiggi.cardcollection.data.repo.SyncProgress

/**
 * Takes the image of a card from the first source that has it: the [sources] are tried in order, those with nothing for the card
 * ([SourceOutcome.NoLink]) are skipped without a request, and a source that fails ([SourceOutcome.Failed]) hands over to the next one.
 * Used both by the single-card button and, for the cards still without an image, by the catalog sync.
 *
 * @param sources List<ImageSource> - in order of preference.
 */
class ImageDownloader(
    private val db: AppDatabase,
    private val images: ImageStore,
    private val sources: List<ImageSource>,
) {
    /**
     * Downloads the image of one card, even if it has one (a manually chosen image is replaced when a source succeeds).
     * @param cardId String - local id of the card.
     * @return ImageResult - DOWNLOADED, NOT_AVAILABLE if no source has anything for the card, NETWORK_ERROR if the sources that had something all failed.
     */
    suspend fun download(cardId: String): ImageResult {
        val card = db.catalogDao().getCard(cardId) ?: return ImageResult.NOT_AVAILABLE
        sources.forEach { it.prepare(listOf(card)) }
        return when (saveFromFirst(card, sources)) {
            Chain.SAVED -> ImageResult.DOWNLOADED
            Chain.FAILED -> ImageResult.NETWORK_ERROR
            Chain.NO_LINK -> ImageResult.NOT_AVAILABLE
        }
    }

    /**
     * Fills the cards of a collection that have no image at all, trying every source in order for each (the first that works wins). Meant for
     * after the catalog sync of that collection, which has already downloaded from TCGdex in parallel: pass its id in [alreadyTried] so it is
     * not asked twice. Other collections' cards are left alone. Failures are not remembered: the next sync tries again, but only for cards
     * that some source has a link for.
     * @param collectionId Long - collection being updated.
     * @param alreadyTried Set<String> - ids of the sources to leave out.
     * @param progress suspend (SyncProgress) -> Unit - progress, once per card that some source has a link for.
     * @return Int - number of cards to which an image was added.
     */
    suspend fun fillMissing(collectionId: Long, alreadyTried: Set<String> = emptySet(), progress: suspend (SyncProgress) -> Unit = {}): Int {
        val chain = sources.filter { it.id !in alreadyTried }
        val missing = db.catalogDao().visibleCardsOf(collectionId).filter { it.imageLowPath == null && it.imageHighPath == null }
        if (missing.isEmpty() || chain.isEmpty()) return 0
        chain.forEach { it.prepare(missing) }
        var saved = 0
        for ((index, card) in missing.withIndex()) {
            progress(SyncProgress(SyncPhase.IMAGES_OTHER, card.name, index, missing.size))
            if (saveFromFirst(card, chain) == Chain.SAVED) saved++
        }
        return saved
    }

    private enum class Chain { SAVED, FAILED, NO_LINK }

    private suspend fun saveFromFirst(card: CardEntity, chain: List<ImageSource>): Chain {
        var failed = false
        for (source in chain) {
            when (val outcome = source.fetch(card)) {
                is SourceOutcome.Saved -> {
                    replaceImage(card.id, outcome.lowPath, outcome.highPath)
                    return Chain.SAVED
                }
                SourceOutcome.Failed -> failed = true
                SourceOutcome.NoLink -> Unit
            }
        }
        return if (failed) Chain.FAILED else Chain.NO_LINK
    }

    /**
     * Saves the new image paths of a card and deletes the files they replace. The file names carry the source, so an image from another source
     * does not overwrite the old one: it has to be removed, or it would stay on the disk with nothing pointing to it.
     */
    private suspend fun replaceImage(cardId: String, low: String, high: String) {
        val current = db.catalogDao().getCard(cardId)
        db.catalogDao().setImagePaths(cardId, low, high)
        images.deleteFiles(listOf(current?.imageLowPath, current?.imageHighPath).filter { it != low && it != high }.distinct())
    }
}
