package com.robertobiggi.cardcollection.core.update

import com.robertobiggi.cardcollection.core.net.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A published release of the app: the version, the address of its APK and what is needed to check the file. */
data class AppRelease(val version: String, val apkUrl: String, val sha256: String?, val size: Long)

/** One file of a GitHub release (`assets` of `GET /repos/{owner}/{repo}/releases/latest`). */
@Serializable
data class GhAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
    /** "sha256:<hex>", published by GitHub for every uploaded file. */
    val digest: String? = null,
)

/** GitHub release (`GET /repos/{owner}/{repo}/releases/latest`). */
@Serializable
data class GhReleaseDto(
    @SerialName("tag_name") val tag: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAssetDto> = emptyList(),
)

/**
 * The app's self-update: finds the latest release of the repository and tells whether it is newer than the installed version.
 * Releases are published by the CI with the tag "v<version>" and one asset, [APK_NAME] (see .github/workflows/android.yml).
 */
object AppUpdate {
    /** Address of the latest release of the repository (GitHub API). */
    const val LATEST_URL = "https://api.github.com/repos/RobertoBiggi/BB-Pokecollector/releases/latest"

    /** Host of the API, to be allowed to the app's HTTP client. */
    const val API_HOST = "api.github.com"

    /** Fixed name of the APK in every release. */
    const val APK_NAME = "bb-pokecollector.apk"

    /** Largest APK accepted (the app is a few MB: a much larger file is not the app). */
    const val MAX_APK_BYTES = 150L * 1024 * 1024

    private const val DOWNLOAD_PREFIX = "/RobertoBiggi/BB-Pokecollector/releases/download/"
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val VERSION = Regex("^[0-9]+(\\.[0-9]+){1,4}$")

    /**
     * Reads the release published as "latest".
     * @param json String - body of the API response.
     * @return AppRelease? - the release, or null if it is a draft or pre-release, has no valid version, or has no APK at an address the app accepts.
     */
    fun parseLatest(json: String): AppRelease? {
        val release = runCatching { Http.JSON.decodeFromString(GhReleaseDto.serializer(), json) }.getOrNull() ?: return null
        if (release.draft || release.prerelease) return null
        val version = release.tag.removePrefix("v")
        if (!VERSION.matches(version)) return null
        val asset = release.assets.firstOrNull { it.name == APK_NAME && isAllowedApkUrl(it.url) && it.size in 1..MAX_APK_BYTES } ?: return null
        val sha256 = asset.digest?.removePrefix("sha256:")?.lowercase()?.takeIf { SHA256.matches(it) }
        return AppRelease(version, asset.url, sha256, asset.size)
    }

    /**
     * Tells whether the APK address is one of this repository's releases (HTTPS, github.com): nothing else is downloaded and installed.
     * @param url String - address.
     * @return Boolean - true if it is accepted.
     */
    fun isAllowedApkUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host == "github.com" && parsed.encodedPath.startsWith(DOWNLOAD_PREFIX) && ".." !in parsed.encodedPath
    }

    /**
     * Compares two versions made of numbers separated by dots ("0.6.45" > "0.6.9" > "0.6").
     * @param latest String - version of the release.
     * @param current String - installed version.
     * @return Boolean - true if [latest] is newer; false if equal, older or if one of the two is not a version.
     */
    fun isNewer(latest: String, current: String): Boolean {
        if (!VERSION.matches(latest) || !VERSION.matches(current)) return false
        val a = latest.split('.').map { it.toLong() }
        val b = current.split('.').map { it.toLong() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
