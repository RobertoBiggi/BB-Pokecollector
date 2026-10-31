package com.robertobiggi.cardcollection.core

/** Reading of the real JSON fixtures captured from TCGdex. */
object Fixtures {
    /**
     * Reads a fixture from the resources/fixtures folder.
     * @param name String - name of the file.
     * @return String - content of the file.
     */
    fun read(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("fixtures/$name")) { "Fixture mancante: $name" }.readText()
}
