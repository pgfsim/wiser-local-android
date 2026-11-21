package dev.wiserlocal.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer release found on GitHub. */
data class AppUpdate(val version: String, val apkUrl: String, val checksumUrl: String?)

/**
 * Optional updates from the project's GitHub releases. Nothing is installed without
 * the user: [install] hands the checked APK to Android, which asks for confirmation.
 */
class AppUpdater(private val context: Context) {

    /** The latest release if it is newer than the installed app, else null. */
    fun check(): AppUpdate? {
        val json = JSONObject(get("${BuildConfig.UPDATE_API}/repos/${BuildConfig.UPDATE_REPO}/releases/latest"))
        val version = json.optString("tag_name").removePrefix("v")
        if (!isNewer(version, BuildConfigInfo.versionName(context))) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: String? = null
        var checksum: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            when {
                name.endsWith(".apk") -> apk = url
                name.endsWith(".apk.sha256") -> checksum = url
            }
        }
        return apk?.let { AppUpdate(version, it, checksum) }
    }

    /**
     * Downloads the update and checks it before anything is installed: the checksum
     * published with the release, and that it is this app, signed with the same key.
     */
    fun download(update: AppUpdate, onProgress: (Float?) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "wiser-local-${update.version}.apk")
        val connection = open(update.apkUrl)
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        onProgress(total?.let { done.toFloat() / it })
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            update.checksumUrl?.let { url ->
                val expected = get(url).trim().substringBefore(' ').lowercase()
                if (expected != actual) throw IOException("The download is damaged (its checksum doesn't match). Try again later.")
            }
            verifyPackage(file)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Opens Android's installer for a file from [download]. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun verifyPackage(file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val downloaded = pm.getPackageArchiveInfo(file.path, flags)
            ?: throw IOException("The download isn't a valid app.")
        if (downloaded.packageName != context.packageName) throw IOException("The download is a different app.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (signers(downloaded.signingInfo?.apkContentsSigners) != signers(installed.signingInfo?.apkContentsSigners)) {
            throw IOException(
                "The update is signed with a different key from this copy of the app, so Android won't install it over this one. " +
                    "Uninstall this copy and install the release from GitHub instead.",
            )
        }
    }

    private fun signers(signatures: Array<Signature>?): Set<String> =
        signatures.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

    private fun get(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "wiser-local-android")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException(if (code == 404) "No release was found on GitHub." else "GitHub answered $code.")
        }
        return connection
    }

    companion object {
        /** True when [candidate] is a higher dotted version than [current] ("0.10.0" > "0.9.1"). */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
