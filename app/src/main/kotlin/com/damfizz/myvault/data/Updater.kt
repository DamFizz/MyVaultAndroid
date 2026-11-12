package com.damfizz.myvault.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.damfizz.myvault.core.Versions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * App updates from GitHub Releases. Only public release metadata is read (no vault data, no account).
 * Android itself refuses to install an update that is not signed with the same key as the installed
 * app, so a tampered download cannot replace MyVault; the published SHA-256 is also checked.
 */
object Updater {
    const val REPO = "DamFizz/MyVaultAndroid"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    class Release(val version: String, val notes: String, val pageUrl: String, val apkUrl: String, val apkName: String, val apkSize: Long, val sha256Url: String?)

    class UpdateError(message: String) : Exception(message)

    private fun get(url: String, accept: String = "application/vnd.github+json"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.useCaches = false
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("User-Agent", "MyVault-Android")
            val code = c.responseCode
            if (code == 404) throw UpdateError("not-found")
            if (code !in 200..299) throw UpdateError("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw UpdateError("network")
        } finally {
            c.disconnect()
        }
    }

    /** The newest published (non-draft) release that has an APK attached, or null when there is none. */
    fun latest(): Release? {
        val list = Json.parseToJsonElement(get("https://api.github.com/repos/$REPO/releases?per_page=15")).jsonArray
        return list.mapNotNull { el ->
            val o = el.jsonObject
            if (o["draft"]?.jsonPrimitive?.boolean == true) return@mapNotNull null
            val assets = o["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val apk = assets.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return@mapNotNull null
            val sha = assets.firstOrNull { it.str("name") == apk.str("name") + ".sha256" }
            Release(
                version = o.str("tag_name")!!.removePrefix("v"),
                notes = o.str("body").orEmpty(),
                pageUrl = o.str("html_url") ?: RELEASES_PAGE,
                apkUrl = apk.str("browser_download_url")!!,
                apkName = apk.str("name")!!,
                apkSize = apk["size"]?.jsonPrimitive?.long ?: 0,
                sha256Url = sha?.str("browser_download_url"),
            )
        }.maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Download the APK into the app's cache, verify its SHA-256 when published, and return the file. */
    fun download(context: Context, r: Release, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, r.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "MyVault-Android")
            if (c.responseCode !in 200..299) throw UpdateError("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (e: IOException) {
            out.delete()
            throw UpdateError("network")
        } finally {
            c.disconnect()
        }
        r.sha256Url?.let { url ->
            val expected = get(url, "text/plain").trim().split(Regex("\\s+")).first().lowercase()
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (expected != actual) {
                out.delete()
                throw UpdateError("checksum")
            }
        }
        return out
    }

    /** Hand the APK to Android's installer (it asks the user to confirm and checks the signature). */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** SHA-1 of the certificate this installed app is signed with (what Google Cloud asks for). */
    fun signingSha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val d = MessageDigest.getInstance("SHA-1").digest(sigs!!.first().toByteArray())
        d.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }.getOrDefault("?")
}
