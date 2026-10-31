package com.robertobiggi.cardcollection.core.model

import java.text.Normalizer

/**
 * Pokémon names in several languages (English, Japanese, French, German, Spanish, Italian, traditional
 * Chinese), indexed by national Pokédex number. Used to find a card by searching for the
 * name in a language different from the card's, e.g. "Omastar" for オムスター.
 * The data comes from PokeAPI (pokemon_species_names.csv, BSD-3-Clause license).
 */
object PokemonNames {

    /** Absolute path of the file included in the module: stays valid even if R8 renames the classes. */
    private const val RESOURCE = "/com/robertobiggi/cardcollection/core/model/pokemon-names.tsv"

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Pokédex number → names already normalized with [normalize], read only once. If the file
     * were missing the search would only work on the card's name (CI checks that it is in the APK).
     */
    private val namesByDex: Map<Int, List<String>> by lazy {
        rawNamesByDex.mapValues { (_, names) -> names.map { normalize(it) }.filter { it.isNotEmpty() } }
    }

    /**
     * Pokédex number → names as written in the file, in column order: Japanese is always
     * first and English last; the other languages may be missing.
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

    /** Minimum length of the searched text in Latin script; for CJK characters 2 are enough (e.g. "伊布"). */
    const val MIN_QUERY_LENGTH = 3

    /** Maximum number of [searchTerms] terms, to avoid making too many requests with a short text. */
    const val MAX_TERMS = 12

    /**
     * Terms to search for a name in the catalog of a language: the searched text and, if it is the name of one or more
     * Pokémon in another language, their name in the catalog's language. This way "Pikachu" finds ピカチュウ in the
     * Japanese catalog. For languages other than English and Japanese only the searched text remains.
     * @param query String - searched text, as written by the user.
     * @param language Language - language of the catalog.
     * @return List<String> - terms to search, the original text first, at most [MAX_TERMS].
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
     * Preloads the names, so the first search does not read the file on the UI thread.
     * @return Int - number of Pokémon loaded.
     */
    fun preload(): Int = namesByDex.size

    /**
     * Makes two names comparable: ignores case, accents, punctuation and spaces, converts
     * hiragana to katakana and full-width or half-width characters to the normal form.
     * E.g. "Flabébé" → "flabebe", "Mr. Mime" → "mrmime", "くちーと" → "クチート".
     * @param text String - text to normalize.
     * @return String - normalized text (may be empty).
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
     * Finds the Pokémon whose name, in any language, contains the searched text.
     * @param query String - searched text, as written by the user.
     * @return Set<Int> - matching Pokédex numbers (empty if the text is too short).
     */
    fun dexIdsMatching(query: String): Set<Int> {
        val q = normalize(query)
        val minLength = if (q.any { isCjk(it) }) 2 else MIN_QUERY_LENGTH
        if (q.length < minLength) return emptySet()
        return namesByDex.filterValues { names -> names.any { it.contains(q) } }.keys
    }

    /**
     * Finds the Pokémon whose name, in any language, appears in the text ("Brock's Golem" → Golem; "Farfetch'd" → Farfetch'd).
     * @param text String - name of a card.
     * @return Set<Int> - Pokédex numbers of the names found (empty if none appears).
     */
    fun dexIdsInName(text: String): Set<Int> {
        val t = normalize(text)
        if (t.isEmpty()) return emptySet()
        return namesByDex.filterValues { names -> names.any { it.length >= (if (it.any(::isCjk)) 2 else MIN_QUERY_LENGTH) && t.contains(it) } }.keys
    }

    /**
     * Tells whether a character is Chinese, Japanese or Korean (a single character counts as a syllable).
     * @param c Char - character to check.
     * @return Boolean - true for ideograms, kana and hangul.
     */
    private fun isCjk(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }
}
