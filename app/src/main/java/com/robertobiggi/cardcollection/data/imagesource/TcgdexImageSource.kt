package com.robertobiggi.cardcollection.data.imagesource

import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.CardEntity
import kotlinx.coroutines.CancellationException

/** TCGdex: the image address saved on the card when it was read from the card database. */
class TcgdexImageSource(private val images: ImageStore) : ImageSource {
    override val id = ID

    override suspend fun fetch(card: CardEntity): SourceOutcome {
        val url = card.imageUrl ?: return SourceOutcome.NoLink
        return try {
            val low = images.ensure(card.id, url, ImageStore.Quality.LOW)
            val high = images.ensure(card.id, url, ImageStore.Quality.HIGH)
            // One quality is enough: the other file stands in for the missing one.
            if (low == null && high == null) SourceOutcome.Failed else SourceOutcome.Saved((low ?: high)!!, (high ?: low)!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceOutcome.Failed
        }
    }

    companion object {
        const val ID = ImageStore.TCGDEX_SOURCE
    }
}
