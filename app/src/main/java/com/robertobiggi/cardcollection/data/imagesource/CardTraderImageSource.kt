package com.robertobiggi.cardcollection.data.imagesource

import com.robertobiggi.cardcollection.core.cardtrader.CardTraderClient
import com.robertobiggi.cardcollection.core.price.CardTraderPriceProvider
import com.robertobiggi.cardcollection.core.price.RateLimiter
import com.robertobiggi.cardcollection.data.image.ImageStore
import com.robertobiggi.cardcollection.data.local.CardEntity
import com.robertobiggi.cardcollection.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * CardTrader: the card's blueprint (from the card database) is looked up through the CardTrader API, which gives the image address.
 * Needs the user's CardTrader token; without it, or for a card with no blueprint, the source has nothing ([SourceOutcome.NoLink]).
 */
class CardTraderImageSource(
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val images: ImageStore,
) : ImageSource {
    override val id = ID
    private val limiter = RateLimiter(REQUEST_INTERVAL_MS)

    override suspend fun fetch(card: CardEntity): SourceOutcome {
        val expansion = card.cardTraderExpansionId ?: return SourceOutcome.NoLink
        val blueprint = card.cardTraderBlueprintId ?: return SourceOutcome.NoLink
        val token = settings.cardTraderToken.first().takeIf { it.isNotEmpty() } ?: return SourceOutcome.NoLink
        return try {
            limiter.acquire()
            // A blueprint that CardTrader has no image for is not an error: nothing to take from here.
            val url = CardTraderPriceProvider(CardTraderClient(http, token)).imageUrl(expansion, blueprint) ?: return SourceOutcome.NoLink
            val (low, high) = images.saveFromCardTrader(card.id, url) ?: return SourceOutcome.Failed
            SourceOutcome.Saved(low, high)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceOutcome.Failed
        }
    }

    companion object {
        const val ID = ImageStore.CARDTRADER_SOURCE
        private const val REQUEST_INTERVAL_MS = 300L
    }
}
