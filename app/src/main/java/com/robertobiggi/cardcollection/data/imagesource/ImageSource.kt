package com.robertobiggi.cardcollection.data.imagesource

import com.robertobiggi.cardcollection.data.local.CardEntity

/** What a source did for a card. */
sealed interface SourceOutcome {
    /** The source has nothing for this card (no link in the database, no token...): skipped, no request was made. */
    data object NoLink : SourceOutcome

    /** The image was downloaded and saved, in low and high quality. */
    data class Saved(val lowPath: String, val highPath: String) : SourceOutcome

    /** The source has a link but the image could not be taken (network error, not found, not an image). */
    data object Failed : SourceOutcome
}

/**
 * A place the app can take a card's image from. [ImageDownloader] tries the sources in order for each card until one saves an image.
 * To add a source: implement this interface and add it to the list in `AppContainer`; for a site whose paths are in the card database,
 * add a `CatalogImageSource` to the core module instead (see [CatalogColumnImageSource]).
 */
interface ImageSource {
    /** Short stable name (logs, [ImageDownloader.fillMissing]'s list of sources already tried). */
    val id: String

    /**
     * Gives the source a chance to read in one go what it needs for many cards (e.g. their paths from the card database), before [fetch] is
     * called for each. Optional.
     */
    suspend fun prepare(cards: List<CardEntity>) {}

    /**
     * Takes the image of a card. It must not throw for ordinary failures (return [SourceOutcome.Failed]); cancellation is rethrown.
     * It saves the files but not the paths in the database: the caller does that.
     */
    suspend fun fetch(card: CardEntity): SourceOutcome
}
