package com.robertobiggi.cardcollection.core.tcgdex

import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.CatalogVariant
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.core.validation.Validators

/** Converte le risposte TCGdex nel modello di catalogo usato dall'app. */
object CatalogMapper {

    /** Serie TCGdex di Pokémon TCG Pocket: carte solo digitali, escluse dalla collezione fisica. */
    private const val POCKET_SERIE_ID = "tcgp"

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
     * Per le carte giapponesi l'id TCGdex coincide con la sigla stampata (es. "SM10a", "M1L");
     * per quelle inglesi si usa l'abbreviazione ufficiale (es. "POR"), assente nelle ere più vecchie.
     * @param language Language - lingua del catalogo.
     * @param set SetDto? - dettaglio del set.
     * @param setId String - id del set, usato per le carte giapponesi.
     * @return String? - sigla stampata, oppure null se la carta non la riporta.
     */
    fun printedSetCode(language: Language, set: SetDto?, setId: String): String? = when (language) {
        Language.JA -> setId
        Language.EN -> set?.abbreviation?.official?.takeIf { it.isNotBlank() }
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
     * Se TCGdex non fornisce il dettaglio, si usano i flag booleani.
     * @param card CardDto - dettaglio della carta.
     * @return List<CatalogVariant> - varianti senza duplicati, nell'ordine dell'enum.
     */
    fun variantsOf(card: CardDto): List<CatalogVariant> {
        val fromDetailed = card.variantsDetailed.orEmpty()
            .filter { it.size == null || it.size == "standard" }
            .mapNotNull { VariantType.fromTcgdexKey(it.type) }
        val types = if (fromDetailed.isNotEmpty()) {
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
        val distinct = types.toSet().ifEmpty { setOf(VariantType.NORMAL) }
        return VariantType.entries.filter { it in distinct }.map { CatalogVariant(it) }
    }

    /**
     * Converte il dettaglio TCGdex in una carta del catalogo locale.
     * @param language Language - lingua del catalogo.
     * @param card CardDto - dettaglio della carta.
     * @param set SetDto? - dettaglio del set (sigla, serie, data), se disponibile.
     * @return CatalogCard - carta normalizzata; l'URL immagine è tenuto solo se l'host è consentito.
     */
    fun toCatalogCard(language: Language, card: CardDto, set: SetDto?): CatalogCard {
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
            variants = variantsOf(card),
        )
    }
}
