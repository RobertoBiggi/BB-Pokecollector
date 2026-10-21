package com.robertobiggi.cardcollection.core.cardtrader

import kotlinx.serialization.json.JsonPrimitive

/** Abbina espansioni e carte di TCGdex a quelle di CardTrader. */
object CardTraderMatcher {

    /**
     * Normalizza un nome di set per il confronto (minuscolo, solo lettere e cifre, senza "pokemon").
     * @param name String - nome del set.
     * @return String - nome normalizzato.
     */
    fun normalizeName(name: String): String =
        name.lowercase().replace("pokémon", "").replace("pokemon", "").filter { it.isLetterOrDigit() }

    /**
     * Normalizza un numero di carta: toglie il totale ("075/110" → "75") e gli zeri iniziali.
     * @param raw String - numero stampato o localId.
     * @return String - numero normalizzato, maiuscolo.
     */
    fun normalizeNumber(raw: String): String {
        val head = raw.substringBefore('/').trim().uppercase()
        val stripped = head.trimStart('0')
        return if (stripped.isEmpty() && head.isNotEmpty()) "0" else stripped
    }

    /**
     * Trova l'espansione di CardTrader corrispondente al set.
     * @param expansions List<CtExpansionDto> - espansioni del gioco.
     * @param setName String - nome del set su TCGdex.
     * @param setId String - id del set su TCGdex.
     * @param setCode String? - sigla stampata del set.
     * @return CtExpansionDto? - espansione trovata, oppure null.
     */
    fun expansionFor(expansions: List<CtExpansionDto>, setName: String, setId: String, setCode: String?): CtExpansionDto? {
        val wanted = normalizeName(setName)
        if (wanted.isNotEmpty()) {
            expansions.firstOrNull { normalizeName(it.name) == wanted }?.let { return it }
        }
        listOfNotNull(setId, setCode).map { it.lowercase() }.forEach { code ->
            expansions.firstOrNull { it.code?.lowercase() == code }?.let { return it }
        }
        // Nomi con prefissi diversi ("SV02: Paldea Evolved"): vale solo se uno contiene l'altro per intero.
        if (wanted.length >= MIN_CONTAINED_LENGTH) {
            val contained = expansions.filter {
                val n = normalizeName(it.name)
                n.length >= MIN_CONTAINED_LENGTH && (n.endsWith(wanted) || wanted.endsWith(n))
            }
            if (contained.size == 1) return contained.first()
        }
        return null
    }

    /**
     * Seleziona i blueprint con il numero di carta indicato.
     * @param blueprints List<CtBlueprintDto> - carte dell'espansione.
     * @param localId String - numero della carta su TCGdex.
     * @return List<CtBlueprintDto> - blueprint con lo stesso numero.
     */
    fun blueprintsFor(blueprints: List<CtBlueprintDto>, localId: String): List<CtBlueprintDto> {
        val wanted = normalizeNumber(localId)
        if (wanted.isEmpty()) return emptyList()
        return blueprints.filter { bp ->
            val number = (bp.fixedProperties?.get("collector_number") as? JsonPrimitive)?.content
            number != null && normalizeNumber(number) == wanted
        }
    }

    private const val MIN_CONTAINED_LENGTH = 6
}
