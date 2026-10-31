package com.robertobiggi.cardcollection.data.image

/**
 * Rules on image paths saved in the database, separate from [ImageStore] because they do not touch the disk
 * (the check for the existence of files is passed in from outside).
 */
object ImagePaths {

    /**
     * Saved paths that no longer match a file: they must be forgotten, so the sync downloads the image again.
     * Happens after a reinstall with Android backup restore, which saves the database but not the images.
     * @param low String? - path of the small image saved in the database.
     * @param high String? - path of the large image saved in the database.
     * @param exists (String) -> Boolean - true if the file at the path exists (see [ImageStore.isSaved]).
     * @return Set<String> - paths to remove; empty if all the files are there.
     */
    fun missing(low: String?, high: String?, exists: (String) -> Boolean): Set<String> =
        setOfNotNull(low, high).filterNot(exists).toSet()
}
