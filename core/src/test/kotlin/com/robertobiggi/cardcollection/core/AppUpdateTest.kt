package com.robertobiggi.cardcollection.core

import com.robertobiggi.cardcollection.core.update.AppRelease
import com.robertobiggi.cardcollection.core.update.AppUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateTest {
    private val sha = "a".repeat(64)
    private fun release(tag: String = "v0.6.45", name: String = "bb-pokecollector.apk", digest: String? = "sha256:$sha", size: Long = 5_000_000, url: String? = null, extra: String = "") =
        """{"tag_name":"$tag","draft":false,"prerelease":false$extra,"assets":[{"name":"$name","size":$size,"digest":${digest?.let { "\"$it\"" } ?: "null"},
            "browser_download_url":"${url ?: "https://github.com/RobertoBiggi/BB-Pokecollector/releases/download/$tag/$name"}"}]}"""

    @Test
    fun `reads the latest release with its checksum`() {
        assertEquals(
            AppRelease("0.6.45", "https://github.com/RobertoBiggi/BB-Pokecollector/releases/download/v0.6.45/bb-pokecollector.apk", sha, 5_000_000),
            AppUpdate.parseLatest(release()),
        )
    }

    @Test
    fun `a release without a usable digest is still read, without checksum`() {
        assertNull(AppUpdate.parseLatest(release(digest = null))!!.sha256)
        assertNull(AppUpdate.parseLatest(release(digest = "sha256:xyz"))!!.sha256)
    }

    @Test
    fun `refuses drafts, pre-releases, odd versions, other files and other addresses`() {
        assertNull(AppUpdate.parseLatest(release(extra = "").replace("\"draft\":false", "\"draft\":true")))
        assertNull(AppUpdate.parseLatest(release().replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(AppUpdate.parseLatest(release(tag = "nightly")))
        assertNull(AppUpdate.parseLatest(release(name = "other.apk")))
        assertNull(AppUpdate.parseLatest(release(size = AppUpdate.MAX_APK_BYTES + 1)))
        assertNull(AppUpdate.parseLatest(release(url = "https://evil.example/RobertoBiggi/BB-Pokecollector/releases/download/v1/bb-pokecollector.apk")))
        assertNull(AppUpdate.parseLatest(release(url = "http://github.com/RobertoBiggi/BB-Pokecollector/releases/download/v1/bb-pokecollector.apk")))
        assertNull(AppUpdate.parseLatest("not json"))
    }

    @Test
    fun `only this repository's release downloads are accepted`() {
        assertTrue(AppUpdate.isAllowedApkUrl("https://github.com/RobertoBiggi/BB-Pokecollector/releases/latest/download/bb-pokecollector.apk").not())
        assertTrue(AppUpdate.isAllowedApkUrl("https://github.com/RobertoBiggi/BB-Pokecollector/releases/download/v0.6.1/bb-pokecollector.apk"))
        assertFalse(AppUpdate.isAllowedApkUrl("https://github.com/Someone/BB-Pokecollector/releases/download/v0.6.1/bb-pokecollector.apk"))
        assertFalse(AppUpdate.isAllowedApkUrl("https://github.com/RobertoBiggi/BB-Pokecollector/releases/download/../../x.apk"))
    }

    @Test
    fun `compares versions number by number`() {
        assertTrue(AppUpdate.isNewer("0.6.45", "0.6.9"))
        assertTrue(AppUpdate.isNewer("0.7.1", "0.6.99"))
        assertTrue(AppUpdate.isNewer("0.6.1", "0.6"))
        assertFalse(AppUpdate.isNewer("0.6.45", "0.6.45"))
        assertFalse(AppUpdate.isNewer("0.6.9", "0.6.45"))
        assertFalse(AppUpdate.isNewer("0.6.45", "dev"))
        assertFalse(AppUpdate.isNewer("v0.6.45", "0.6.1"))
    }
}
