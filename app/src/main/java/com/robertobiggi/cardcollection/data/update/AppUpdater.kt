package com.robertobiggi.cardcollection.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.robertobiggi.cardcollection.BuildConfig
import com.robertobiggi.cardcollection.core.net.Http
import com.robertobiggi.cardcollection.core.update.AppRelease
import com.robertobiggi.cardcollection.core.update.AppUpdate
import com.robertobiggi.cardcollection.work.UpdateInstallReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The app's self-update: looks for a newer release on GitHub, downloads its APK and hands it to the system installer.
 * Android only installs it over the current app if it is signed with the same key, so a wrong file cannot replace the app; the SHA-256 that GitHub
 * publishes for the file is checked too.
 *
 * @param context application context.
 * @param http client of the app (with the list of allowed hosts): used to ask for the latest release.
 * @param downloadHttp client for the APK, which GitHub serves from another host after a redirect: the address is checked before, see [AppUpdate.isAllowedApkUrl].
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient, private val downloadHttp: OkHttpClient) {

    private val dir get() = File(context.cacheDir, DIR)

    /**
     * Asks GitHub for the latest release.
     * @return AppRelease? - the release if it is newer than the installed version; null if not, or if it cannot be read (it will be asked again at the next opening).
     */
    suspend fun newerRelease(): AppRelease? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(AppUpdate.LATEST_URL).header("Accept", "application/vnd.github+json").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                AppUpdate.parseLatest(Http.readLimited(response))?.takeIf { AppUpdate.isNewer(it.version, BuildConfig.VERSION_NAME) }
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Downloads the APK of a release to the app's cache and checks it.
     * @param release AppRelease - release to download.
     * @param progress suspend (Long, Long) -> Unit - bytes downloaded and expected.
     * @return File? - the checked file, or null if the download failed or the file is not the expected one.
     */
    suspend fun download(release: AppRelease, progress: suspend (Long, Long) -> Unit = { _, _ -> }): File? = withContext(Dispatchers.IO) {
        if (!AppUpdate.isAllowedApkUrl(release.apkUrl) || release.size !in 1..AppUpdate.MAX_APK_BYTES) return@withContext null
        dir.mkdirs()
        clear()
        val target = File(dir, "bb-pokecollector-${release.version}.apk")
        val part = File(dir, "${target.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            downloadHttp.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > release.size) return@withContext null
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            progress(done, release.size)
                        }
                    }
                }
                if (done != release.size) return@withContext null
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && release.sha256 != hex) return@withContext null
            if (part.renameTo(target)) target else null
        } catch (e: IOException) {
            null
        } finally {
            part.delete()
        }
    }

    /**
     * Gives the APK to the system installer; the result arrives at [UpdateInstallReceiver] (the system asks the user to confirm).
     * @param apk File - checked APK.
     * @return Boolean - true if the installer accepted the file (the installation then goes on in the system).
     */
    suspend fun install(apk: File): Boolean = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
                val result = PendingIntent.getBroadcast(
                    context, id, Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * True if the user has allowed this app to install apps (a system setting, off by default).
     * @return Boolean - true if it can.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Deletes the APKs downloaded earlier.
     * @return Unit
     */
    fun clear() {
        dir.listFiles()?.filter { it.extension == "apk" }?.forEach { it.delete() }
    }

    private companion object {
        const val DIR = "update"
        const val BUFFER = 64 * 1024
    }
}
