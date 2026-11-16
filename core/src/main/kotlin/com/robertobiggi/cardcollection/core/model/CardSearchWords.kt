package com.robertobiggi.cardcollection.core.model

/**
 * Searches made of a Pokémon name plus words about the set ("pikachu 2010", "pikachu world collection", "charizard base"): the broad search by Pokémon
 * returns thousands of cards, so the extra words narrow it to the sets they name.
 */
object CardSearchWords {

    /**
     * Splits a search into the Pokémon name and the set words, trying the first words, then the last ones, as the Pokémon (the longest group first).
     * @param words List<String> - words of the search, without empty ones.
     * @param isPokemon (String) -> Boolean - true if the text names a Pokémon.
     * @return Pair<String, List<String>>? - the Pokémon text and the other words; null if there is only one word or no group of words names a Pokémon.
     */
    fun split(words: List<String>, isPokemon: (String) -> Boolean): Pair<String, List<String>>? {
        if (words.size < 2) return null
        for (k in words.size - 1 downTo 1) {
            val head = words.take(k).joinToString(" ")
            if (isPokemon(head)) return head to words.drop(k)
        }
        for (k in words.size - 1 downTo 1) {
            val tail = words.takeLast(k).joinToString(" ")
            if (isPokemon(tail)) return tail to words.dropLast(k)
        }
        return null
    }

    /**
     * Tells whether a set fits all the words: each one is in the set's name, id, code or series, or is the year (or the start of the date) of its release.
     * @param words List<String> - set words of the search.
     * @param setName String - name of the set.
     * @param setId String - id of the set.
     * @param setCode String? - printed code of the set.
     * @param serieName String? - name of the series.
     * @param releaseDate String? - release date ("2010-04-01").
     * @return Boolean - true if every word fits.
     */
    fun matchesSet(words: List<String>, setName: String, setId: String, setCode: String?, serieName: String?, releaseDate: String?): Boolean =
        words.all { word ->
            setName.contains(word, ignoreCase = true) || setId.contains(word, ignoreCase = true) || setCode?.contains(word, ignoreCase = true) == true ||
                serieName?.contains(word, ignoreCase = true) == true || releaseDate?.startsWith(word) == true
        }
}
