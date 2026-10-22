package com.robertobiggi.cardcollection.core.tcgdex

import com.robertobiggi.cardcollection.core.cardmarket.BallVariants
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.CatalogVariant
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.validation.Validators

/** Converte le risposte TCGdex nel modello di catalogo usato dall'app. */
object CatalogMapper {

    /** Serie TCGdex di Pokémon TCG Pocket: carte solo digitali, escluse dalla collezione fisica. */
    private const val POCKET_SERIE_ID = "tcgp"

    /** Sigle stampate che TCGdex scrive con un altro id (il World Champions Pack giapponese è "WCP", per TCGdex "PCG10"). */
    private val PRINTED_CODES = mapOf("PCG10" to "WCP")
    private const val FIRST_REVERSE_HOLO_DATE = "2002-05-24"

    /**
     * Costruisce l'id locale univoco di una carta.
     * @param language Language - lingua del catalogo.
     * @param tcgdexId String - id TCGdex della carta.
     * @return String - id nel formato "lingua:idTcgdex".
     */
    fun localCardId(language: Language, tcgdexId: String): String = "${language.code}:$tcgdexId"

    /**
     * Ricava l'id del set dall'id di una carta della lista (la parte prima dell'ultimo trattino).
     * @param cardId String - id TCGdex della carta, es. "me02.5-144".
     * @return String? - id del set, es. "me02.5", oppure null se il formato non è riconosciuto.
     */
    fun setIdFromCardId(cardId: String): String? {
        val index = cardId.lastIndexOf('-')
        if (index <= 0) return null
        return cardId.substring(0, index).takeIf { Validators.isValidRemoteId(it) }
    }

    /**
     * Indica se un elemento della lista va scartato prima di scaricarne il dettaglio
     * (le carte di TCG Pocket hanno "/tcgp/" nell'URL dell'immagine).
     * @param brief CardBriefDto - elemento della lista di ricerca.
     * @return Boolean - true se la carta è certamente solo digitale.
     */
    fun isDigitalOnly(brief: CardBriefDto): Boolean = brief.image?.contains("/$POCKET_SERIE_ID/") == true

    /**
     * Indica se la carta va tracciata: solo carte Pokémon fisiche (esclude Allenatori, Energie e TCG Pocket).
     * @param card CardDto - dettaglio della carta.
     * @param set SetDto? - dettaglio del set, se disponibile.
     * @return Boolean - true se la carta va inclusa nella collezione.
     */
    fun isTrackablePokemon(card: CardDto, set: SetDto?): Boolean {
        if (!card.category.equals("Pokemon", ignoreCase = true)) return false
        if (set?.serie?.id == POCKET_SERIE_ID) return false
        if (card.image?.contains("/$POCKET_SERIE_ID/") == true) return false
        return true
    }

    /**
     * Ricava la sigla del set stampata in basso a sinistra.
     * Nei cataloghi asiatici l'id TCGdex coincide con la sigla stampata (es. "SM10a", "M1L");
     * per le altre lingue si usa l'abbreviazione localizzata o quella ufficiale (es. "POR"),
     * assente nelle ere più vecchie.
     * @param language Language - lingua del catalogo.
     * @param set SetDto? - dettaglio del set.
     * @param setId String - id del set, usato per le carte giapponesi.
     * @return String? - sigla stampata, oppure null se la carta non la riporta.
     */
    fun printedSetCode(language: Language, set: SetDto?, setId: String): String? =
        if (language.asianSets) {
            PRINTED_CODES[setId] ?: setId
        } else {
            (set?.abbreviation?.localized ?: set?.abbreviation?.official)?.takeIf { it.isNotBlank() }
        }

    /**
     * Compone il numero stampato sulla carta.
     * Se il numero locale è numerico aggiunge il totale ufficiale con lo stesso numero di cifre
     * (es. "032" e 88 → "032/088"); altrimenti, come per le promo, restituisce il numero così com'è.
     * @param localId String - numero della carta nel set (es. "032", "9", "XY103").
     * @param officialCount Int? - numero ufficiale di carte del set.
     * @return String - numero stampato.
     */
    fun printedNumber(localId: String, officialCount: Int?): String {
        if (officialCount == null || officialCount <= 0 || !localId.all { it.isDigit() }) return localId
        return "$localId/${officialCount.toString().padStart(localId.length, '0')}"
    }

    /**
     * Elenca le varianti standard della carta. Le carte jumbo vengono ignorate.
     * Se TCGdex non fornisce il dettaglio, o lo fornisce con nomi tradotti (es. "Olografica" nel
     * catalogo italiano), si usano i flag booleani, che hanno sempre chiavi standard.
     * @param card CardDto - dettaglio della carta.
     * @param releaseDate String? - data di uscita del set (ISO), per sapere se la reverse holo esisteva già; senza, non se ne deduce nessuna dai prezzi.
     * @return List<CatalogVariant> - varianti senza duplicati, nell'ordine dell'enum.
     */
    fun variantsOf(card: CardDto, releaseDate: String? = null, balls: Set<VariantType> = emptySet()): List<CatalogVariant> {
        val standard = card.variantsDetailed.orEmpty().filter { it.size == null || it.size == "standard" }
        val fromDetailed = standard.mapNotNull { VariantType.fromTcgdexKey(it.type) }
        val types = if (fromDetailed.isNotEmpty() && fromDetailed.size == standard.size) {
            fromDetailed
        } else {
            val flags = card.variants ?: VariantFlagsDto(normal = true)
            buildList {
                if (flags.normal) add(VariantType.NORMAL)
                if (flags.holo) add(VariantType.HOLO)
                if (flags.reverse) add(VariantType.REVERSE)
                if (flags.firstEdition) add(VariantType.FIRST_EDITION)
                if (flags.wPromo) add(VariantType.W_PROMO)
            }
        }
        val distinct = types.toSet().ifEmpty { setOf(VariantType.NORMAL) }.let { if (hasReverseHoloPrice(card, it, releaseDate)) it + VariantType.REVERSE else it }
            .let { if (balls.isEmpty()) it else it - VariantType.REVERSE + balls }
        return VariantType.entries.filter { it in distinct }.map { CatalogVariant(it) }
    }

    /**
     * Dice se Cardmarket quota una reverse holo che TCGdex non elenca tra le varianti. I prezzi "-holo" sono quelli della
     * reverse holo, ma solo se il prodotto ha anche i prezzi base (normal o holo rare): una carta con i soli prezzi "-holo"
     * è una holo e basta. TCGdex a volte dimentica il flag (es. Omastar Team Up 76/181 e Tag Bolt 057/095), mentre i prezzi ci sono.
     */
    private fun hasReverseHoloPrice(card: CardDto, types: Set<VariantType>, releaseDate: String?): Boolean {
        // Le prime reverse holo escono con Legendary Collection; le carte BREAK non ne hanno. Prima e per quelle, i prezzi "-holo" sono altro (1ª edizione, ristampe).
        if (releaseDate == null || releaseDate < FIRST_REVERSE_HOLO_DATE || card.name.endsWith("BREAK")) return false
        if (VariantType.REVERSE in types || (VariantType.NORMAL !in types && VariantType.HOLO !in types)) return false
        val cm = card.pricing?.cardmarket ?: return false
        val base = cm.trend.let { it != null && it > 0 }
        val reverse = cm.trendHolo.let { it != null && it > 0 }
        return base && reverse
    }

    /**
     * Converte il dettaglio TCGdex in una carta del catalogo locale.
     * @param language Language - lingua del catalogo.
     * @param card CardDto - dettaglio della carta.
     * @param set SetDto? - dettaglio del set (sigla, serie, data), se disponibile.
     * @return CatalogCard - carta normalizzata; l'URL immagine è tenuto solo se l'host è consentito.
     */
    fun toCatalogCard(language: Language, card: CardDto, set: SetDto?, ballVariants: BallVariants = BallVariants.bundled): CatalogCard {
        val image = card.image?.takeIf { Validators.isAllowedImageUrl(it) }
        return CatalogCard(
            id = localCardId(language, card.id),
            language = language,
            tcgdexId = card.id,
            name = card.name.take(120),
            setId = card.set.id,
            setName = (set?.name ?: card.set.name).take(120),
            setCode = printedSetCode(language, set, card.set.id)?.take(16),
            serieId = set?.serie?.id,
            serieName = set?.serie?.name?.take(80),
            localId = card.localId.take(16),
            printedNumber = printedNumber(card.localId, card.set.cardCount?.official ?: set?.cardCount?.official).take(24),
            rarity = card.rarity?.takeIf { it != "None" }?.take(60),
            releaseDate = set?.releaseDate?.take(10),
            illustrator = card.illustrator?.take(80),
            imageBaseUrl = image,
            variants = variantsOf(card, set?.releaseDate, if (language == Language.JA) ballVariants.variantsOf(card.set.id, card.localId) else emptySet()),
            dexId = card.dexId?.firstOrNull(),
        )
    }
}
