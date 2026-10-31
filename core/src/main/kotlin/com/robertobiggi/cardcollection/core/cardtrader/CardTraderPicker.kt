package com.robertobiggi.cardcollection.core.cardtrader

import com.robertobiggi.cardcollection.core.model.Condition
import com.robertobiggi.cardcollection.core.model.VariantType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Conditions of CardTrader listings, from best to worst. For each variant the condition of the
 * owned copy or a better one is accepted: [NEAR_MINT] accepts Mint and Near Mint, [POOR] any.
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
         * Converts the name saved in the settings.
         * @param name String? - name of the enum.
         * @return CtCondition - the condition, [NEAR_MINT] if absent or unknown.
         */
        fun fromName(name: String?): CtCondition = entries.firstOrNull { it.name == name } ?: NEAR_MINT

        /**
         * Translates the condition of the owned copy (Cardmarket scale) into the CardTrader one.
         * @param condition Condition - condition of the variant.
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
         * Converts the condition written in a listing.
         * @param label String? - text of `properties_hash.condition`.
         * @return CtCondition? - the condition, or null if missing or unknown.
         */
        fun fromLabel(label: String?): CtCondition? = entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

/** Lowest price found among the listings. */
data class CtPick(val cents: Long, val currency: String)

/**
 * Picks the price of a variant among the CardTrader listings.
 *
 * CardTrader has no page per rarity: reverse holo and normal of the same card are in the
 * same blueprint and are told apart only by the listing properties. For each variant the right
 * listings are therefore kept and the cheapest is taken.
 */
object CardTraderPicker {
    /**
     * Finds the cheapest listing for the variant.
     * @param products List<CtProductDto> - listings of one or more blueprints of the same card.
     * @param variant VariantType - requested variant.
     * @param languageCode String? - requested language (e.g. "en"), null for no filter.
     * @param worstCondition CtCondition - worst accepted condition; listings without a known condition stay excluded.
     * @param firstEditionFallback Boolean - if a normal/holo variant has no "non 1st Edition" listing, use the cheapest among the 1st Edition ones.
     *        Needed for Japanese cards, which for entire sets only come with the 1st Edition mark and which TCGdex does not tell apart.
     * @param splitHolo Boolean - true if the card has both normal and holo: normal and holo share the blueprint, so each keeps only
     *        the listings whose rarity (`pokemon_rarity`) is that of its print. An absent or ambiguous rarity counts for both.
     * @param lowerCondition Boolean - if no listing meets [worstCondition], goes down one condition at a time
     *        (Near Mint → Slightly Played → … → Poor) and uses the first price found; if missing even in Poor, no price.
     * @return CtPick? - lowest price, or null if no listing matches.
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
            // They have a separate blueprint (set "... Poké Ball Reverse Holo"): there all listings are of the variant.
            VariantType.POKE_BALL, VariantType.MASTER_BALL, VariantType.ENERGY_REVERSE -> !firstEdition
            // Oversized cards with their own blueprint (Jumbo Oversized): there all listings are of the variant.
            VariantType.JUMBO -> !firstEdition
            // CardTrader does not tell the other separate prints apart: their prices cannot be derived from the card's listings.
            VariantType.SHADOWLESS, VariantType.RED_CHEEK, VariantType.POKETOUR_99, VariantType.STAMPED, VariantType.CARDMARKET_PRINT -> false
        }
    }

    /**
     * Tells whether the rarity of the listing is that of the requested print. "Common", "Uncommon" and "Rare" are non-holo, any other
     * rarity ("Holo Rare", "Ultra Rare"…) is holo; without rarity, or with an ambiguous one ("Promo"), the listing counts for both.
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

    /** Reads a boolean flag such as `pokemon_reverse` (accepts true, "true" and 1). */
    private fun flag(props: JsonObject?, suffix: String): Boolean {
        val value = props?.entries?.firstOrNull { it.key.endsWith(suffix) }?.value as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: (value.content == "1")
    }
}
