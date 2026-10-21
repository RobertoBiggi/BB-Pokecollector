package com.robertobiggi.cardcollection.core.model

import java.text.Normalizer

/**
 * Nomi dei Pokémon in più lingue (inglese, giapponese, francese, tedesco, spagnolo, italiano, cinese
 * tradizionale), indicizzati per numero del Pokédex nazionale. Serve a trovare una carta cercando il
 * nome in una lingua diversa da quella della carta, es. "Omastar" per オムスター.
 * I dati vengono da PokeAPI (pokemon_species_names.csv, licenza BSD-3-Clause).
 */
object PokemonNames {

    /** Percorso assoluto del file incluso nel modulo: resta valido anche se R8 rinomina le classi. */
    private const val RESOURCE = "/com/robertobiggi/cardcollection/core/model/pokemon-names.tsv"

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Numero del Pokédex → nomi già normalizzati con [normalize], letti una sola volta. Se il file
     * mancasse la ricerca resterebbe solo sul nome della carta (la CI controlla che sia nell'APK).
     */
    private val namesByDex: Map<Int, List<String>> by lazy {
        val stream = PokemonNames::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line ->
                val parts = line.split('\t')
                parts[0].toInt() to parts.drop(1).map { normalize(it) }.filter { it.isNotEmpty() }
            }
        }
    }

    /** Lunghezza minima del testo cercato in alfabeto latino; per i caratteri CJK bastano 2 (es. "伊布"). */
    const val MIN_QUERY_LENGTH = 3

    /**
     * Carica in anticipo i nomi, così la prima ricerca non legge il file sul thread dell'interfaccia.
     * @return Int - numero di Pokémon caricati.
     */
    fun preload(): Int = namesByDex.size

    /**
     * Rende confrontabili due nomi: ignora maiuscole, accenti, punteggiatura e spazi, converte
     * l'hiragana in katakana e i caratteri a larghezza piena o ridotta nella forma normale.
     * Es. "Flabébé" → "flabebe", "Mr. Mime" → "mrmime", "くちーと" → "クチート".
     * @param text String - testo da normalizzare.
     * @return String - testo normalizzato (può essere vuoto).
     */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val noMarks = COMBINING_MARKS.replace(Normalizer.normalize(folded, Normalizer.Form.NFD), "")
        val katakana = buildString(noMarks.length) {
            for (c in noMarks) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
        }
        return NOT_LETTER_OR_DIGIT.replace(Normalizer.normalize(katakana, Normalizer.Form.NFC), "")
    }

    /**
     * Trova i Pokémon il cui nome, in una qualsiasi lingua, contiene il testo cercato.
     * @param query String - testo cercato, così come l'ha scritto l'utente.
     * @return Set<Int> - numeri del Pokédex corrispondenti (vuoto se il testo è troppo corto).
     */
    fun dexIdsMatching(query: String): Set<Int> {
        val q = normalize(query)
        val minLength = if (q.any { isCjk(it) }) 2 else MIN_QUERY_LENGTH
        if (q.length < minLength) return emptySet()
        return namesByDex.filterValues { names -> names.any { it.contains(q) } }.keys
    }

    /**
     * Indica se un carattere è cinese, giapponese o coreano (un solo carattere vale quanto una sillaba).
     * @param c Char - carattere da controllare.
     * @return Boolean - true per ideogrammi, kana e hangul.
     */
    private fun isCjk(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }
}
