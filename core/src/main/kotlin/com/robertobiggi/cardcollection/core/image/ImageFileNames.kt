package com.robertobiggi.cardcollection.core.image

/**
 * Names of the image files. The name carries the source the image was taken from ("<card>-high-pokellector.webp"), so the app can say where the
 * picture it shows comes from without a column in the database: the label always matches the file actually shown, and a new image
 * from another source has a new name (the screen does not keep showing the old one from its cache).
 * Files saved by earlier versions, or imported from a backup, have no source in the name: their origin is unknown.
 */
object ImageFileNames {
    /** Source of the images the user picked by hand. */
    const val MANUAL = "manual"

    private val SOURCE = Regex("[a-z0-9]{1,20}")
    private val NAME = Regex("""-(?:low|high)(?:-([a-z0-9]{1,20}))?\.webp$""")
    private val MANUAL_NAME = Regex("""-manual-\d+\.webp$""")

    /**
     * Name of a downloaded image.
     * @param segment String - the card id made safe for a file name.
     * @param quality String - "low" or "high".
     * @param source String? - source id (lowercase letters and digits), or null if unknown.
     * @return String - file name.
     * @throws IllegalArgumentException if the source id has other characters (it would end up in a path).
     */
    fun of(segment: String, quality: String, source: String?): String {
        require(source == null || SOURCE.matches(source)) { "Fonte non valida: $source" }
        return "$segment-$quality${source?.let { "-$it" }.orEmpty()}.webp"
    }

    /**
     * Source an image file comes from.
     * @param fileName String - name of the file (not the whole path).
     * @return String? - the source id, [MANUAL] for an image the user picked, or null if the name does not say.
     */
    fun sourceOf(fileName: String): String? =
        if (MANUAL_NAME.containsMatchIn(fileName)) MANUAL else NAME.find(fileName)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
}
