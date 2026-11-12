package com.damfizz.myvault.data

import com.damfizz.myvault.core.RemoteError
import com.damfizz.myvault.core.RemoteFileMeta
import com.damfizz.myvault.core.RemoteStore
import com.damfizz.myvault.core.SyncFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Google Drive v3 REST client limited to the private appDataFolder — port of the web's `sync/drive.ts`.
 * Only ciphertext is ever uploaded. Calls block; run them off the main thread.
 */
class DriveRemote(private val token: () -> String) : RemoteStore {
    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"
    private val fields = "id,name,version,modifiedTime,size"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun request(url: String, method: String = "GET", contentType: String? = null, body: String? = null): String {
        val c: HttpURLConnection
        val code: Int
        val text: String
        try {
            c = URL(url).openConnection() as HttpURLConnection
            // HttpURLConnection has no PATCH; Drive accepts the override header on POST.
            c.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.setRequestProperty("Authorization", "Bearer ${token()}")
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.useCaches = false
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            code = c.responseCode
            text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
        } catch (e: IOException) {
            throw RemoteError("network", "Could not reach Google Drive. Check your internet connection.")
        }
        if (code in 200..299) return text
        val reason = runCatching {
            Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["errors"]!!.jsonArray[0].jsonObject["reason"]!!.jsonPrimitive.content
        }.getOrDefault("")
        throw when {
            code == 401 -> RemoteError("auth", "Your Google session expired. Please reconnect.", 401)
            code == 404 -> RemoteError("not-found", "Cloud vault file not found.", 404)
            code == 429 || reason == "userRateLimitExceeded" || reason == "rateLimitExceeded" -> RemoteError("rate-limit", "Google Drive is rate limiting requests. Sync will retry later.", code)
            reason == "storageQuotaExceeded" -> RemoteError("quota", "Your Google Drive storage is full.", code)
            code == 403 && (reason == "insufficientPermissions" || reason == "accessNotConfigured") -> RemoteError("config", "Google Drive access is not configured correctly (is the Drive API enabled?).", 403)
            code == 403 -> RemoteError("auth", "Google Drive denied access. Please reconnect.", 403)
            code >= 500 -> RemoteError("server", "Google Drive is temporarily unavailable.", code)
            else -> RemoteError("server", "Unexpected Google Drive response ($code).", code)
        }
    }

    private fun meta(o: JsonObject) = RemoteFileMeta(o["id"]!!.jsonPrimitive.content, o["version"]!!.jsonPrimitive.content)

    override fun find(): RemoteFileMeta? {
        val q = enc("name = '${SyncFormat.SYNC_FILE_NAME}' and trashed = false")
        val body = request("$api/files?spaces=appDataFolder&q=$q&orderBy=${enc("modifiedTime desc")}&pageSize=10&fields=${enc("files($fields)")}")
        return Json.parseToJsonElement(body).jsonObject["files"]!!.jsonArray.firstOrNull()?.jsonObject?.let(::meta)
    }

    override fun getMeta(id: String): RemoteFileMeta? = try {
        meta(Json.parseToJsonElement(request("$api/files/${enc(id)}?fields=${enc(fields)}")).jsonObject)
    } catch (e: RemoteError) {
        if (e.code == "not-found") null else throw e
    }

    override fun download(id: String): String = request("$api/files/${enc(id)}?alt=media")

    override fun create(content: String): RemoteFileMeta {
        val boundary = "myvault-${UUID.randomUUID()}"
        val metadata = """{"name":"${SyncFormat.SYNC_FILE_NAME}","parents":["appDataFolder"],"mimeType":"application/json"}"""
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n$content\r\n--$boundary--"
        return meta(Json.parseToJsonElement(request("$upload/files?uploadType=multipart&fields=${enc(fields)}", "POST", "multipart/related; boundary=$boundary", body)).jsonObject)
    }

    override fun update(id: String, content: String): RemoteFileMeta =
        meta(Json.parseToJsonElement(request("$upload/files/${enc(id)}?uploadType=media&fields=${enc(fields)}", "PATCH", "application/json", content)).jsonObject)

    override fun remove(id: String) {
        request("$api/files/${enc(id)}", "DELETE")
    }

    /** Email and name of the signed-in Google account (shown in the sync screen). */
    fun about(): Pair<String?, String?> {
        val user = Json.parseToJsonElement(request("$api/about?fields=${enc("user(displayName,emailAddress)")}")).jsonObject["user"]?.jsonObject
        return user?.get("emailAddress")?.jsonPrimitive?.contentOrNull to user?.get("displayName")?.jsonPrimitive?.contentOrNull
    }
}
