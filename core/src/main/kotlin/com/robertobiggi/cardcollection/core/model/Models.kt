package com.robertobiggi.cardcollection.core.model

/**
 * TCGdex catalog language. [code] is the segment used in the API URLs.
 * [asianSets] is true for Asian catalogs, where the set id coincides with the printed code.
 * [limitedData] is true when TCGdex has few cards, almost without images or prices (verified with the probe).
 */
enum class Language(val code: String, val asianSets: Boolean = false, val limitedData: Boolean = false) {
    EN("en"),
    IT("it"),
    FR("fr"),
    DE("de"),
    ES("es"),
    PT("pt"),
    JA("ja", asianSets = true),
    ZH_TW("zh-tw", asianSets = true, limitedData = true),
    ZH_CN("zh-cn", asianSets = true, limitedData = true),
    TH("th", asianSets = true, limitedData = true),
    ID("id", asianSets = true, limitedData = true);

    companion object {
        /**
         * Converts a language code into the enum value.
         * @param code String - ISO code used by TCGdex ("en", "ja").
         * @return Language? - the matching language, or null if not supported.
         */
        fun fromCode(code: String): Language? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Language of a collection, saved as text: the code of a fixed language ([FIXED]) or [MIXED].
 * In a fixed-language collection all searches use that language; in a mixed one the language is
 * chosen for each search, among all those of [Language].
 */
object CollectionLanguage {

    /** Value saved for mixed collections. */
    const val MIXED = "mixed"

    /** Languages that can be chosen as the fixed language of a collection. */
    val FIXED: List<Language> = listOf(Language.EN, Language.JA, Language.ZH_TW, Language.ZH_CN)

    /**
     * Gets the fixed language from the saved value.
     * @param code String? - value saved in the database or in the file.
     * @return Language? - fixed language, or null if the collection is mixed or the value is not recognized.
     */
    fun fixedLanguage(code: String?): Language? = FIXED.firstOrNull { it.code == code }

    /**
     * Converts a user choice into the value to save.
     * @param language Language? - fixed language chosen, or null for a mixed collection.
     * @return String - language code, or [MIXED] if the language is not among those in [FIXED].
     */
    fun codeOf(language: Language?): String = language?.takeIf { it in FIXED }?.code ?: MIXED

    /**
     * Checks a value read from an imported file.
     * @param code String? - value read.
     * @return String? - the value if valid ([MIXED] or a language in [FIXED]), otherwise null.
     */
    fun sanitize(code: String?): String? = code?.takeIf { it == MIXED || fixedLanguage(it) != null }

    /**
     * Infers the language of a collection from the languages of its searches and cards.
     * @param languageCodes Collection<String> - language codes found.
     * @return String - the language if it is only one and among those in [FIXED], otherwise [MIXED].
     */
    fun infer(languageCodes: Collection<String>): String = codeOf(languageCodes.distinct().singleOrNull()?.let(Language::fromCode))
}

/** Print variant of a card. Each variant is a "slot" of the collection. */
enum class VariantType(val tcgdexKey: String) {
    NORMAL("normal"),
    HOLO("holo"),
    REVERSE("reverse"),
    POKE_BALL("pokeBall"),
    MASTER_BALL("masterBall"),
    ENERGY_REVERSE("energyReverse"),
    SHADOWLESS("shadowless"),
    RED_CHEEK("redCheek"),
    FIRST_EDITION("firstEdition"),
    W_PROMO("wPromo"),
    POKETOUR_99("poketour99"),
    JUMBO("jumbo"),

    /** Print with a stamp (tournament, prerelease, staff…): each stamp is a variant, given by `print`. */
    STAMPED("stamped"),

    /** Other print of the card that Cardmarket lists and TCGdex does not: `print` is its Cardmarket product. */
    CARDMARKET_PRINT("cardmarketPrint");

    /** Poké Ball, Master Ball or Energy reverse holo (Ascended Heroes, MEGA Dream ex): separate Cardmarket products that TCGdex does not list as such. */
    val isBall: Boolean get() = this == POKE_BALL || this == MASTER_BALL || this == ENERGY_REVERSE

    /** Separate print of the same card (shadowless, red cheeks, Pokétour '99, jumbo): it has its own Cardmarket product, which TCGdex gives for each. */
    val isPrint: Boolean get() = this == SHADOWLESS || this == RED_CHEEK || this == POKETOUR_99 || this == JUMBO || this == STAMPED || this == CARDMARKET_PRINT

    companion object {
        /**
         * Finds the variant from the name saved in the database or in files (e.g. "HOLO").
         * @param name String? - name of the enum.
         * @return VariantType? - variant, or null if the name is not recognized.
         */
        fun fromName(name: String?): VariantType? = entries.firstOrNull { it.name == name }
    }
}

/** Condition of the owned copy, with the scale used by Cardmarket. */
enum class Condition { NM, LP, MP, HP, DMG }

/** How reliable a price is with respect to the variant it is associated with. */
enum class Confidence {
    /** The source explicitly distinguishes this variant. */
    HIGH,

    /** The source gives a price for the product but does not separate the variant well. */
    MEDIUM,

    /** Partial or derived data. */
    LOW,

    /** Entered manually by the user. */
    MANUAL,
}

/**
 * Variant available for a catalog card.
 * @property cardmarketId Cardmarket product of the variant, only for separate prints ([VariantType.isPrint]); the others use that of the card.
 * @property print tells apart several variants of the same type: the stamp of [VariantType.STAMPED] or the product of [VariantType.CARDMARKET_PRINT]; empty for the others.
 * @property cardTraderBlueprintId CardTrader blueprint of the variant alone, for oversized cards ([VariantType.JUMBO]) without a Cardmarket product: the price comes from there.
 */
data class CatalogVariant(
    val type: VariantType,
    val cardmarketId: Int? = null,
    val print: String = "",
    val cardTraderBlueprintId: Int? = null,
) {
    /** Key of the variant in the card, as in [Ids.variantKey]. */
    val key: String get() = Ids.variantKey(type, print)
}

/**
 * Catalog card already normalized, ready to be saved in the local database.
 *
 * @property id unique local identifier in the format "language:tcgdexId" (e.g. "en:me01-094").
 * @property setCode code printed at the bottom left (e.g. "POR", "M1L"), null if the card does not show it.
 * @property dexId national Pokédex number, null for Trainers and Energies (used to search the card by name in every language).
 * @property printedNumber printed number (e.g. "094/132" or "XY103" for promos).
 * @property cardmarketId Cardmarket product id, only for cards found in the Cardmarket catalog: identifies the card
 *           without going through the number, which for these cards is inferred from the position.
 * @property cardmarketExpansionId Cardmarket set of the card (`idExpansion`), for the direct link; null if it could not be derived.
 * @property cardmarketProductHint Cardmarket product id that TCGdex gives for the card: helps CardTrader find it when the number is not enough (e.g. Celebrations Classic Collection).
 * @property cardmarketName English name of the card on Cardmarket, to search in its set (the card's name may be in another language).
 * @property cardmarketProduct Cardmarket product of the card, however found by the database generator (the price is read from there).
 * @property cardTraderBlueprintId CardTrader blueprint of the card, if the generator matched it.
 * @property cardTraderExpansionId CardTrader expansion of the card's set (needed to find the blueprint for the image).
 */
data class CatalogCard(
    val id: String,
    val language: Language,
    val tcgdexId: String,
    val name: String,
    val setId: String,
    val setName: String,
    val setCode: String?,
    val serieId: String?,
    val serieName: String?,
    val localId: String,
    val printedNumber: String,
    val rarity: String?,
    val releaseDate: String?,
    val illustrator: String?,
    val imageBaseUrl: String?,
    val variants: List<CatalogVariant>,
    val dexId: Int? = null,
    val cardmarketId: Int? = null,
    val cardmarketExpansionId: Int? = null,
    val cardmarketName: String? = null,
    val cardmarketProductHint: Int? = null,
    val cardmarketProduct: Int? = null,
    val cardTraderBlueprintId: Int? = null,
    val cardTraderExpansionId: Int? = null,
)

/**
 * Price found for a variant.
 *
 * @property source name of the source (e.g. "Cardmarket").
 * @property eur reference price in euros (Cardmarket trend, lowest CardTrader listing), null if absent.
 * @property eurLow lowest price in euros, null if absent.
 * @property eurAvg30 30-day average in euros, null if absent.
 * @property sourceUpdatedAt update date declared by the source (ISO-8601), if present.
 */
data class PriceQuote(
    val source: String,
    val eur: Double?,
    val eurLow: Double?,
    val eurAvg30: Double?,
    val sourceUpdatedAt: String?,
    val confidence: Confidence,
) {
    /**
     * Tells whether the price contains a usable value.
     * @return Boolean - true if the euro price is present.
     */
    fun hasAnyPrice(): Boolean = eur != null
}

/** Local identifiers of cards and variants. */
object Ids {
    private val VARIANT_ID = Regex(
        "^(" + Language.entries.joinToString("|") { Regex.escape(it.code) } + "):[A-Za-z0-9][A-Za-z0-9._\\-]{0,39}\\|([A-Z_0-9]+)(?:\\|([a-z0-9][a-z0-9+\\-]{0,79}))?$",
    )
    private val PRINT = Regex("^[a-z0-9][a-z0-9+\\-]{0,79}$")

    /**
     * Builds the id of a variant (key of the card_variant table).
     * @param cardId String - local id of the card ("en:me01-094").
     * @param type VariantType - variant.
     * @return String - id in the format "en:me01-094|HOLO".
     */
    fun variantId(cardId: String, type: VariantType, print: String = ""): String = "$cardId|${variantKey(type, print)}"

    /**
     * Key of a variant within the card: the type, followed by the stamp or product if there is one.
     * @param type VariantType - variant.
     * @param print String - stamp or product, empty if not needed.
     * @return String - e.g. "HOLO" or "STAMPED|chris-fulop".
     */
    fun variantKey(type: VariantType, print: String = ""): String = if (print.isEmpty()) type.name else "${type.name}|$print"

    /**
     * Reads a variant key (see [variantKey]).
     * @param key String - key, also with just the type name.
     * @return Pair<VariantType, String>? - type and stamp/product, or null if the type or the stamp are not valid.
     */
    fun parseVariantKey(key: String): Pair<VariantType, String>? {
        val type = VariantType.fromName(key.substringBefore('|')) ?: return null
        val print = key.substringAfter('|', "")
        if (print.isNotEmpty() && !PRINT.matches(print)) return null
        return type to print
    }

    /**
     * Tells whether a card comes from the Cardmarket catalog without a number (id "en:s-p-Cardmarket786632").
     * @param cardId String - local id of the card.
     * @return Boolean - true if it is a card without a number identified by the Cardmarket product.
     */
    fun isUnnumberedCardmarket(cardId: String): Boolean = UNNUMBERED.containsMatchIn(cardId)

    private val UNNUMBERED = Regex("-Cardmarket\\d+$")

    /**
     * Checks the format of a variant id coming from a backup.
     * @param id String - id to check.
     * @return Boolean - true if the id has the expected format and a known variant.
     */
    fun isValidVariantId(id: String): Boolean {
        val match = VARIANT_ID.matchEntire(id) ?: return false
        return !id.contains("..") && VariantType.entries.any { it.name == match.groupValues[2] }
    }
}
