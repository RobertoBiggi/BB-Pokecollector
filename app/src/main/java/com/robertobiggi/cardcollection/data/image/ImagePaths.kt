package com.robertobiggi.cardcollection.data.image

/**
 * Regole sui percorsi delle immagini salvati nel database, separate da [ImageStore] perché non toccano il disco
 * (il controllo dell'esistenza dei file viene passato da fuori).
 */
object ImagePaths {

    /**
     * Percorsi salvati che non corrispondono più a un file: vanno dimenticati, così il sync riscarica l'immagine.
     * Succede dopo una reinstallazione con ripristino del backup Android, che salva il database ma non le immagini.
     * @param low String? - percorso dell'immagine piccola salvato nel database.
     * @param high String? - percorso dell'immagine grande salvato nel database.
     * @param exists (String) -> Boolean - true se il file del percorso esiste (vedi [ImageStore.isSaved]).
     * @return Set<String> - percorsi da togliere; vuoto se i file ci sono tutti.
     */
    fun missing(low: String?, high: String?, exists: (String) -> Boolean): Set<String> =
        setOfNotNull(low, high).filterNot(exists).toSet()
}
