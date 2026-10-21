package com.robertobiggi.cardcollection.core.cardtrader

import com.robertobiggi.cardcollection.core.model.VariantType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Condizioni degli annunci CardTrader, dalla migliore alla peggiore. Nelle impostazioni si sceglie
 * la peggiore accettata: [NEAR_MINT] accetta Mint e Near Mint, [POOR] accetta qualsiasi condizione.
 */
enum class CtCondition(val label: String) {
    MINT("Mint"),
    NEAR_MINT("Near Mint"),
    SLIGHTLY_PLAYED("Slightly Played"),
    MODERATELY_PLAYED("Moderately Played"),
    PLAYED("Played"),
    POOR("Poor");

    companion object {
        /**
         * Converte il nome salvato nelle impostazioni.
         * @param name String? - nome dell'enum.
         * @return CtCondition - la condizione, [NEAR_MINT] se assente o sconosciuta.
         */
        fun fromName(name: String?): CtCondition = entries.firstOrNull { it.name == name } ?: NEAR_MINT

        /**
         * Converte la condizione scritta in un annuncio.
         * @param label String? - testo di `properties_hash.condition`.
         * @return CtCondition? - la condizione, oppure null se mancante o sconosciuta.
         */
        fun fromLabel(label: String?): CtCondition? = entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

/** Prezzo più basso trovato tra gli annunci. */
data class CtPick(val cents: Long, val currency: String)

/**
 * Sceglie il prezzo di una variante tra gli annunci di CardTrader.
 *
 * CardTrader non ha una pagina per rarità: reverse holo e normal della stessa carta stanno nello
 * stesso blueprint e si distinguono solo dalle proprietà dell'annuncio. Per ogni variante si tengono
 * quindi gli annunci giusti e si prende il più economico.
 */
object CardTraderPicker {
    /**
     * Trova l'annuncio più economico per la variante.
     * @param products List<CtProductDto> - annunci di uno o più blueprint della stessa carta.
     * @param variant VariantType - variante richiesta.
     * @param languageCode String? - lingua richiesta (es. "en"), null per non filtrare.
     * @param worstCondition CtCondition - condizione peggiore accettata; gli annunci senza condizione nota restano esclusi.
     * @return CtPick? - prezzo più basso, oppure null se nessun annuncio corrisponde.
     */
    fun lowest(
        products: List<CtProductDto>,
        variant: VariantType,
        languageCode: String?,
        worstCondition: CtCondition = CtCondition.NEAR_MINT,
    ): CtPick? {
        val matching = products.filter { p ->
            p.graded != true && (p.quantity ?: 1) > 0 && priceOf(p) != null &&
                matchesVariant(p.propertiesHash, variant) && matchesLanguage(p.propertiesHash, languageCode) &&
                (condition(p.propertiesHash)?.let { it <= worstCondition } ?: false)
        }
        return matching.mapNotNull { priceOf(it) }.minByOrNull { it.cents }
    }

    private fun priceOf(p: CtProductDto): CtPick? {
        val cents = p.priceCents ?: p.price?.cents
        val currency = p.priceCurrency ?: p.price?.currency
        if (cents == null || cents <= 0 || currency == null) return null
        return CtPick(cents, currency.uppercase())
    }

    private fun matchesVariant(props: JsonObject?, variant: VariantType): Boolean {
        val reverse = flag(props, "reverse")
        val firstEdition = flag(props, "first_edition")
        return when (variant) {
            VariantType.REVERSE -> reverse
            VariantType.FIRST_EDITION -> firstEdition
            VariantType.NORMAL, VariantType.HOLO, VariantType.W_PROMO -> !reverse && !firstEdition
        }
    }

    private fun matchesLanguage(props: JsonObject?, languageCode: String?): Boolean {
        if (languageCode == null || props == null) return true
        val value = props.entries.firstOrNull { it.key.endsWith("_language") }?.value as? JsonPrimitive ?: return true
        return value.content.lowercase() == languageCode
    }

    private fun condition(props: JsonObject?): CtCondition? =
        CtCondition.fromLabel((props?.get("condition") as? JsonPrimitive)?.content)

    /** Legge un flag booleano del tipo `pokemon_reverse` (accetta true, "true" e 1). */
    private fun flag(props: JsonObject?, suffix: String): Boolean {
        val value = props?.entries?.firstOrNull { it.key.endsWith(suffix) }?.value as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: (value.content == "1")
    }
}
