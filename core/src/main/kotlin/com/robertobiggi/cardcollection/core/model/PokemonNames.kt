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
        rawNamesByDex.mapValues { (_, names) -> names.map { normalize(it) }.filter { it.isNotEmpty() } }
    }

    /**
     * Numero del Pokédex → nomi come scritti nel file, nell'ordine delle colonne: il giapponese è sempre il
     * primo e l'inglese l'ultimo; le altre lingue possono mancare.
     */
    private val rawNamesByDex: Map<Int, List<String>> by lazy {
        val stream = PokemonNames::class.java.getResourceAsStream(RESOURCE) ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.associate { line ->
                val parts = line.split('\t')
                parts[0].toInt() to parts.drop(1).filter { it.isNotBlank() }
            }
        }
    }

    /** Lunghezza minima del testo cercato in alfabeto latino; per i caratteri CJK bastano 2 (es. "伊布"). */
    const val MIN_QUERY_LENGTH = 3

    /** Numero massimo di termini di [searchTerms], per non fare troppe richieste con un testo breve. */
    const val MAX_TERMS = 12

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

    /** Nome (solo lettere, minuscolo) → numero del Pokédex; i nomi che appartengono a più Pokémon sono esclusi. */
    private val dexByKey: Map<String, Int> by lazy {
        val all = HashMap<String, Int?>()
        for ((dex, names) in namesByDex) for (n in names) {
            val key = n.replace('♀', 'f').replace('♂', 'm').filter { it.isLetter() }
            if (key.length < 3) continue
            all[key] = if (all.containsKey(key) && all[key] != dex) null else dex
        }
        all.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    /**
     * Ricava il numero del Pokédex dal nome di una carta ("Dark Omastar Lv.33" → 139).
     * @param text String - nome della carta.
     * @return Int? - il Pokédex, oppure null se nel nome non c'è un Pokémon o ce ne sono più di uno (es. un Allenatore).
     */
    fun dexIdOfName(text: String): Int? =
        text.lowercase().replace('♀', 'f').replace('♂', 'm').split(Regex("[^\\p{L}]+"))
            .mapNotNull { dexByKey[it] }.distinct().singleOrNull()

    /**
     * Nomi di un Pokémon in tutte le lingue.
     * @param dexId Int - numero del Pokédex nazionale.
     * @return List<String> - nomi in minuscolo, vuoto se il numero è sconosciuto.
     */
    fun namesOf(dexId: Int): List<String> = namesByDex[dexId].orEmpty()

    /**
     * Termini con cui cercare un nome nel catalogo di una lingua: il testo cercato e, se è il nome di uno o più
     * Pokémon in un'altra lingua, il loro nome in quella del catalogo. Così "Pikachu" trova ピカチュウ nel catalogo
     * giapponese. Per le lingue diverse da inglese e giapponese resta solo il testo cercato.
     * @param query String - testo cercato, così come l'ha scritto l'utente.
     * @param language Language - lingua del catalogo.
     * @return List<String> - termini da cercare, il testo originale per primo, al massimo [MAX_TERMS].
     */
    fun searchTerms(query: String, language: Language): List<String> {
        val index = when (language) {
            Language.JA -> 0
            Language.EN -> -1
            else -> return listOf(query)
        }
        val translated = dexIdsMatching(query).sorted().mapNotNull { dex ->
            val names = rawNamesByDex[dex].orEmpty()
            if (index == 0) names.firstOrNull() else names.lastOrNull()
        }
        return (listOf(query) + translated).distinct().take(MAX_TERMS)
    }

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
