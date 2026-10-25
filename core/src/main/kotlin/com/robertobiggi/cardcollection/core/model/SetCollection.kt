package com.robertobiggi.cardcollection.core.model

import com.robertobiggi.cardcollection.core.validation.Validators

/**
 * Tipo di una ricerca salvata in una collezione.
 *
 * @property code valore salvato nel database e nei file JSON.
 */
enum class QueryKind(val code: String) {
    /** Ricerca per nome (es. "Mawile"): tutte le carte che contengono quel nome. */
    NAME("name"),

    /** Set intero con uno slot per carta (la variante principale). */
    SET_BASE("set_base"),

    /** Set intero con tutte le varianti (master set), senza la distinzione 1st Edition. */
    SET_MASTER("set_master");

    /** True per le ricerche che scaricano un set intero. */
    val isSet: Boolean get() = this != NAME

    companion object {
        /**
         * Converte il valore salvato nel tipo di ricerca.
         * @param code String? - valore letto da database o file; null per i dati salvati prima dei set.
         * @return QueryKind? - tipo corrispondente, NAME se il valore manca, null se non è riconosciuto.
         */
        fun fromCode(code: String?): QueryKind? = if (code == null) NAME else entries.firstOrNull { it.code == code }

        /**
         * Controlla il testo di una ricerca in base al tipo.
         * @param kind QueryKind - tipo di ricerca.
         * @param pattern String - nome da cercare oppure id del set (es. "base1", "M3").
         * @return Boolean - true se il testo è valido per quel tipo.
         */
        fun isValidPattern(kind: QueryKind, pattern: String): Boolean =
            if (kind.isSet) Validators.isValidRemoteId(pattern.trim()) else Validators.isValidQueryPattern(pattern)
    }
}

/**
 * Regole delle collezioni per set: quali varianti di una carta non contano come slot.
 * Le varianti escluse restano nel catalogo e si possono sempre segnare a mano come "la voglio".
 */
object SetCollectionPolicy {

    /** Ordine con cui si sceglie la variante principale di una carta nel set base. */
    private val MAIN_ORDER = listOf(
        VariantType.NORMAL,
        VariantType.HOLO,
        VariantType.REVERSE,
        VariantType.POKE_BALL,
        VariantType.MASTER_BALL,
        VariantType.W_PROMO,
        VariantType.FIRST_EDITION,
    )

    /**
     * Variante principale di una carta: normal, altrimenti holo, altrimenti la prima disponibile.
     * @param types Collection<VariantType> - varianti della carta.
     * @return VariantType? - variante principale, oppure null se la carta non ha varianti.
     */
    fun mainVariant(types: Collection<VariantType>): VariantType? = MAIN_ORDER.firstOrNull { it in types }

    /**
     * Varianti da segnare come "non la voglio" in una collezione per set.
     * Nel master set si esclude solo la 1st Edition (se la carta ha anche altre varianti); nel set base
     * tutto tranne la variante principale. Per le ricerche per nome non si esclude nulla.
     * @param types Collection<VariantType> - varianti della carta.
     * @param kind QueryKind - tipo di ricerca che ha aggiunto la carta.
     * @return Set<VariantType> - varianti escluse.
     */
    fun unwantedVariants(types: Collection<VariantType>, kind: QueryKind): Set<VariantType> = when (kind) {
        QueryKind.NAME -> emptySet()
        QueryKind.SET_MASTER ->
            if (VariantType.FIRST_EDITION in types && types.any { it != VariantType.FIRST_EDITION }) setOf(VariantType.FIRST_EDITION) else emptySet()
        QueryKind.SET_BASE -> {
            val main = mainVariant(types)
            types.filter { it != main }.toSet()
        }
    }
}
