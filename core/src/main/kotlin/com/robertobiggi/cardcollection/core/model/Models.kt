package com.robertobiggi.cardcollection.core.model

/**
 * Lingua del catalogo TCGdex. [code] è il segmento usato negli URL dell'API.
 * [asianSets] vale true per i cataloghi asiatici, dove l'id del set coincide con la sigla stampata.
 * [limitedData] vale true quando TCGdex ha poche carte, quasi senza immagini né prezzi (verificato col probe).
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
    TH("th", asianSets = true, limitedData = true),
    ID("id", asianSets = true, limitedData = true);

    companion object {
        /**
         * Converte un codice lingua nel valore dell'enum.
         * @param code String - codice ISO usato da TCGdex ("en", "ja").
         * @return Language? - la lingua corrispondente, oppure null se non supportata.
         */
        fun fromCode(code: String): Language? = entries.firstOrNull { it.code == code }
    }
}

/** Variante di stampa di una carta. Ogni variante è uno "slot" della collezione. */
enum class VariantType(val tcgdexKey: String) {
    NORMAL("normal"),
    HOLO("holo"),
    REVERSE("reverse"),
    FIRST_EDITION("firstEdition"),
    W_PROMO("wPromo");

    companion object {
        /**
         * Converte la chiave di variante usata da TCGdex nel valore dell'enum.
         * @param key String - chiave TCGdex ("normal", "holo", "reverse", "firstEdition", "wPromo").
         * @return VariantType? - la variante corrispondente, oppure null se sconosciuta.
         */
        fun fromTcgdexKey(key: String): VariantType? = entries.firstOrNull { it.tcgdexKey == key }

        /**
         * Trova la variante dal nome salvato nel database o nei file (es. "HOLO").
         * @param name String? - nome dell'enum.
         * @return VariantType? - variante, oppure null se il nome non è riconosciuto.
         */
        fun fromName(name: String?): VariantType? = entries.firstOrNull { it.name == name }
    }
}

/** Condizione della copia posseduta, con la scala usata da Cardmarket. */
enum class Condition { NM, LP, MP, HP, DMG }

/** Quanto è affidabile un prezzo rispetto alla variante a cui è associato. */
enum class Confidence {
    /** La fonte distingue esplicitamente questa variante. */
    HIGH,

    /** La fonte dà un prezzo del prodotto ma non separa bene la variante. */
    MEDIUM,

    /** Dato parziale o derivato. */
    LOW,

    /** Inserito a mano dall'utente. */
    MANUAL,
}

/** Variante disponibile per una carta del catalogo. */
data class CatalogVariant(
    val type: VariantType,
)

/**
 * Carta del catalogo già normalizzata, pronta per essere salvata nel database locale.
 *
 * @property id identificativo univoco locale nel formato "lingua:idTcgdex" (es. "en:me01-094").
 * @property setCode sigla stampata in basso a sinistra (es. "POR", "M1L"), null se la carta non la riporta.
 * @property dexId numero del Pokédex nazionale, null per Allenatori ed Energie (serve a cercare la carta per nome in ogni lingua).
 * @property printedNumber numero stampato (es. "094/132" oppure "XY103" per le promo).
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
)

/**
 * Prezzo trovato per una variante.
 *
 * @property source nome della fonte (es. "TCGdex").
 * @property eur prezzo di riferimento Cardmarket in euro (trend), null se assente.
 * @property eurLow prezzo minimo Cardmarket in euro, null se assente.
 * @property eurAvg30 media a 30 giorni Cardmarket, null se assente.
 * @property usd prezzo di riferimento TCGplayer in dollari (market), null se assente.
 * @property usdLow prezzo minimo TCGplayer in dollari, null se assente.
 * @property sourceUpdatedAt data di aggiornamento dichiarata dalla fonte (ISO-8601), se presente.
 */
data class PriceQuote(
    val source: String,
    val eur: Double?,
    val eurLow: Double?,
    val eurAvg30: Double?,
    val usd: Double?,
    val usdLow: Double?,
    val sourceUpdatedAt: String?,
    val confidence: Confidence,
) {
    /**
     * Indica se il prezzo contiene almeno un valore utilizzabile.
     * @return Boolean - true se è presente un prezzo in euro o in dollari.
     */
    fun hasAnyPrice(): Boolean = eur != null || usd != null
}

/** Identificativi locali di carte e varianti. */
object Ids {
    private val VARIANT_ID = Regex(
        "^(" + Language.entries.joinToString("|") { Regex.escape(it.code) } + "):[A-Za-z0-9][A-Za-z0-9._\\-]{0,39}\\|([A-Z_]+)$",
    )

    /**
     * Costruisce l'id di una variante (chiave della tabella card_variant).
     * @param cardId String - id locale della carta ("en:me01-094").
     * @param type VariantType - variante.
     * @return String - id nel formato "en:me01-094|HOLO".
     */
    fun variantId(cardId: String, type: VariantType): String = "$cardId|${type.name}"

    /**
     * Controlla il formato di un id di variante proveniente da un backup.
     * @param id String - id da controllare.
     * @return Boolean - true se l'id ha il formato atteso e una variante conosciuta.
     */
    fun isValidVariantId(id: String): Boolean {
        val match = VARIANT_ID.matchEntire(id) ?: return false
        return !id.contains("..") && VariantType.entries.any { it.name == match.groupValues[2] }
    }
}
