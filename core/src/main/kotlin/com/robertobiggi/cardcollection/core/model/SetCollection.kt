package com.robertobiggi.cardcollection.core.model

import com.robertobiggi.cardcollection.core.validation.Validators

/**
 * Type of a search saved in a collection.
 *
 * @property code value saved in the database and in JSON files.
 */
enum class QueryKind(val code: String) {
    /** Search by name (e.g. "Mawile"): all the cards that contain that name. */
    NAME("name"),

    /** Entire set with one slot per card (the main variant). */
    SET_BASE("set_base"),

    /** Entire set with all variants (master set), without the 1st Edition distinction. */
    SET_MASTER("set_master");

    /** True for searches that download an entire set. */
    val isSet: Boolean get() = this != NAME

    companion object {
        /**
         * Converts the saved value into the search type.
         * @param code String? - value read from the database or a file; null for data saved before sets.
         * @return QueryKind? - matching type, NAME if the value is missing, null if it is not recognized.
         */
        fun fromCode(code: String?): QueryKind? = if (code == null) NAME else entries.firstOrNull { it.code == code }

        /**
         * Checks the text of a search according to its type.
         * @param kind QueryKind - type of search.
         * @param pattern String - name to search or set id (e.g. "base1", "M3").
         * @return Boolean - true if the text is valid for that type.
         */
        fun isValidPattern(kind: QueryKind, pattern: String): Boolean =
            if (kind.isSet) Validators.isValidRemoteId(pattern.trim()) else Validators.isValidQueryPattern(pattern)
    }
}

/**
 * Rules of set collections: which variants of a card do not count as slots.
 * Excluded variants stay in the catalog and can always be marked by hand as "I want it".
 */
object SetCollectionPolicy {

    /** Order used to choose the main variant of a card in the base set. */
    private val MAIN_ORDER = listOf(
        VariantType.NORMAL,
        VariantType.HOLO,
        VariantType.REVERSE,
        VariantType.POKE_BALL,
        VariantType.MASTER_BALL,
        VariantType.ENERGY_REVERSE,
        VariantType.W_PROMO,
        VariantType.FIRST_EDITION,
        VariantType.SHADOWLESS,
        VariantType.RED_CHEEK,
        VariantType.POKETOUR_99,
        VariantType.JUMBO,
        VariantType.STAMPED,
        VariantType.CARDMARKET_PRINT,
    )

    /**
     * Main variant of a card: normal, otherwise holo, otherwise the first available.
     * @param types Collection<VariantType> - variants of the card.
     * @return VariantType? - main variant, or null if the card has no variants.
     */
    fun mainVariant(types: Collection<VariantType>): VariantType? = MAIN_ORDER.firstOrNull { it in types }

    /**
     * Variants to mark as "I don't want it" in a set collection.
     * In the master set the 1st Edition and separate prints are excluded (Shadowless, Red cheeks, Pokétour '99, Jumbo,
     * stamps and other Cardmarket prints, see [VariantType.isPrint]), provided the card also has another variant:
     * the master set counts finishes (normal, holo, reverse, Poké Ball, Master Ball), not editions.
     * In the base set everything except the main variant is excluded. For searches by name nothing is excluded.
     * @param types Collection<VariantType> - variants of the card.
     * @param kind QueryKind - type of search that added the card.
     * @return Set<VariantType> - excluded variants.
     */
    fun unwantedVariants(types: Collection<VariantType>, kind: QueryKind): Set<VariantType> = when (kind) {
        QueryKind.NAME -> emptySet()
        QueryKind.SET_MASTER -> {
            val editions = types.filter { it == VariantType.FIRST_EDITION || it.isPrint }.toSet()
            if (types.any { it !in editions }) editions else emptySet()
        }
        QueryKind.SET_BASE -> {
            val main = mainVariant(types)
            types.filter { it != main }.toSet()
        }
    }
}
