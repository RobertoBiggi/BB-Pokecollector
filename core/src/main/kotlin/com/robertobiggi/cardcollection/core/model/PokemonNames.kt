package com.robertobiggi.cardcollection.core.model

/**
 * Nomi dei Pokémon in più lingue (inglese, giapponese, francese, tedesco, spagnolo, italiano, cinese
 * tradizionale), indicizzati per numero del Pokédex nazionale. Serve a trovare una carta cercando il
 * nome in una lingua diversa da quella della carta, es. "Omastar" per オムスター.
 * I dati vengono da PokeAPI (pokemon_species_names.csv).
 */
object PokemonNames {

    /** Numero del Pokédex → nomi in minuscolo, letti una sola volta dal file incluso nel modulo. */
    private val namesByDex: Map<Int, List<String>> by lazy {
        val stream = PokemonNames::class.java.getResourceAsStream("pokemon-names.tsv") ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line ->
                val parts = line.split('\t')
                parts[0].toInt() to parts.drop(1).map { it.lowercase() }
            }
        }
    }

    /** Lunghezza minima del testo cercato: sotto non ha senso estendere la ricerca ai nomi in altre lingue. */
    const val MIN_QUERY_LENGTH = 3

    /** Tutti i nomi di Pokémon (solo lettere, minuscoli), per riconoscere le carte Pokémon dal nome. */
    private val allNameKeys: Set<String> by lazy {
        namesByDex.values.flatten().map { n -> n.replace('♀', 'f').replace('♂', 'm').filter { it.isLetter() } }.filter { it.length >= 3 }.toSet()
    }

    /**
     * Dice se un testo (es. il nome di una carta) contiene il nome di un Pokémon come parola intera.
     * @param text String - testo da controllare, es. "Dark Omastar Lv.33".
     * @return Boolean - true se una parola è il nome di un Pokémon in una qualsiasi lingua.
     */
    fun mentionsPokemon(text: String): Boolean =
        text.lowercase().replace('♀', 'f').replace('♂', 'm').split(Regex("[^\\p{L}]+")).any { it in allNameKeys }

    /**
     * Nomi di un Pokémon in tutte le lingue.
     * @param dexId Int - numero del Pokédex nazionale.
     * @return List<String> - nomi in minuscolo, vuoto se il numero è sconosciuto.
     */
    fun namesOf(dexId: Int): List<String> = namesByDex[dexId].orEmpty()

    /**
     * Trova i Pokémon il cui nome, in una qualsiasi lingua, contiene il testo cercato.
     * @param query String - testo cercato, già in minuscolo.
     * @return Set<Int> - numeri del Pokédex corrispondenti (vuoto se il testo è troppo corto).
     */
    fun dexIdsMatching(query: String): Set<Int> {
        if (query.length < MIN_QUERY_LENGTH) return emptySet()
        return namesByDex.filterValues { names -> names.any { it.contains(query) } }.keys
    }
}
