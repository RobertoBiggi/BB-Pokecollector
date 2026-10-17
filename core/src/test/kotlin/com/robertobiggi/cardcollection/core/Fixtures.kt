package com.robertobiggi.cardcollection.core

/** Lettura delle fixture JSON reali catturate da TCGdex. */
object Fixtures {
    /**
     * Legge una fixture dalla cartella resources/fixtures.
     * @param name String - nome del file.
     * @return String - contenuto del file.
     */
    fun read(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("fixtures/$name")) { "Fixture mancante: $name" }.readText()
}
