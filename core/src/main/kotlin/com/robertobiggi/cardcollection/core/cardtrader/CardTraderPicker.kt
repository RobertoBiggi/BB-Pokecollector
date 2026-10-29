package com.robertobiggi.cardcollection.core.cardtrader

import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.VariantType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Condizioni degli annunci CardTrader, dalla migliore alla peggiore. Per ogni variante si accetta la
 * condizione della copia posseduta o una migliore: [NEAR_MINT] accetta Mint e Near Mint, [POOR] qualsiasi.
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
         * Traduce la condizione della copia posseduta (scala Cardmarket) in quella di CardTrader.
         * @param condition Condition - condizione della variante.
         * @return CtCondition - NM → Near Mint, LP → Slightly Played, MP → Moderately Played, HP → Played, DMG → Poor.
         */
        fun fromCondition(condition: Condition): CtCondition = when (condition) {
            Condition.NM -> NEAR_MINT
            Condition.LP -> SLIGHTLY_PLAYED
            Condition.MP -> MODERATELY_PLAYED
            Condition.HP -> PLAYED
            Condition.DMG -> POOR
        }

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
     * @param firstEditionFallback Boolean - se per una variante normal/holo non c'è nessun annuncio "non 1st Edition", usa i più economici tra quelli 1st Edition.
     *        Serve alle carte giapponesi, che per interi set escono solo con il marchio 1st Edition e che TCGdex non distingue.
     * @param splitHolo Boolean - true se la carta ha sia normal sia holo: normal e holo condividono il blueprint, quindi ognuna tiene solo
     *        gli annunci la cui rarità (`pokemon_rarity`) è quella della sua stampa. Una rarità assente o ambigua vale per entrambe.
     * @param lowerCondition Boolean - se nessun annuncio rispetta [worstCondition], scende una condizione alla volta
     *        (Near Mint → Slightly Played → … → Poor) e usa il primo prezzo trovato; se manca anche in Poor, nessun prezzo.
     * @return CtPick? - prezzo più basso, oppure null se nessun annuncio corrisponde.
     */
    fun lowest(
        products: List<CtProductDto>,
        variant: VariantType,
        languageCode: String?,
        worstCondition: CtCondition = CtCondition.NEAR_MINT,
        firstEditionFallback: Boolean = false,
        lowerCondition: Boolean = false,
        splitHolo: Boolean = false,
    ): CtPick? {
        val candidates = products.filter { p ->
            p.graded != true && (p.quantity ?: 1) > 0 && priceOf(p) != null &&
                matchesLanguage(p.propertiesHash, languageCode) && condition(p.propertiesHash) != null &&
                (!splitHolo || matchesPrint(p.propertiesHash, variant))
        }
        val pools = buildList {
            add(candidates.filter { matchesVariant(it.propertiesHash, variant) })
            if (firstEditionFallback && variant in FIRST_EDITION_FALLBACK_VARIANTS) {
                add(candidates.filter { !flag(it.propertiesHash, "reverse") && flag(it.propertiesHash, "first_edition") })
            }
        }
        val levels = if (lowerCondition) CtCondition.entries.filter { it >= worstCondition } else listOf(worstCondition)
        for (pool in pools) {
            for (level in levels) {
                pool.filter { condition(it.propertiesHash)!! <= level }.mapNotNull { priceOf(it) }.minByOrNull { it.cents }?.let { return it }
            }
        }
        return null
    }

    private val FIRST_EDITION_FALLBACK_VARIANTS = setOf(VariantType.NORMAL, VariantType.HOLO, VariantType.W_PROMO)

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
            // Hanno un blueprint a parte (set "... Poké Ball Reverse Holo"): lì sono tutti annunci della variante.
            VariantType.POKE_BALL, VariantType.MASTER_BALL, VariantType.ENERGY_REVERSE -> !firstEdition
            // CardTrader non distingue le stampe a parte: i loro prezzi non si ricavano dagli annunci della carta.
            VariantType.SHADOWLESS, VariantType.RED_CHEEK, VariantType.POKETOUR_99, VariantType.JUMBO, VariantType.STAMPED, VariantType.CARDMARKET_PRINT -> false
        }
    }

    /**
     * Dice se la rarità dell'annuncio è quella della stampa richiesta. "Common", "Uncommon" e "Rare" sono non holo, ogni altra
     * rarità ("Holo Rare", "Ultra Rare"…) è holo; senza rarità, o con una ambigua ("Promo"), l'annuncio vale per entrambe.
     */
    private fun matchesPrint(props: JsonObject?, variant: VariantType): Boolean {
        if (variant != VariantType.NORMAL && variant != VariantType.HOLO) return true
        val rarity = (props?.entries?.firstOrNull { it.key.endsWith("rarity") }?.value as? JsonPrimitive)?.content?.trim()?.lowercase()
        return when {
            rarity.isNullOrEmpty() || rarity in AMBIGUOUS_RARITIES -> true
            rarity in NON_HOLO_RARITIES -> variant == VariantType.NORMAL
            else -> variant == VariantType.HOLO
        }
    }

    private val NON_HOLO_RARITIES = setOf("common", "uncommon", "rare")
    private val AMBIGUOUS_RARITIES = setOf("promo", "fixed", "none")

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
