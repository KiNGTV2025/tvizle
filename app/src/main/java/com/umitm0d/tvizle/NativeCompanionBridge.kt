package com.umitm0d.tvizle

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.JavascriptInterface
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.net.HttpURLConnection
import java.net.URL

/** Native companion services for ÜmitTV's Stremio/Trakt and TV interfaces.
 *  Secrets are encrypted with an Android Keystore AES-GCM key; only the trusted app page can call this bridge. */
class NativeCompanionBridge(
    private val context: Context,
    private val trustedPage: () -> Boolean,
    private val tvDevice: () -> Boolean
) {
    private val prefs by lazy { context.getSharedPreferences("umit_companion_secure_v1", Context.MODE_PRIVATE) }
    private val alias = "umit_companion_aes_v1"
    private val allowedKeys = setOf("stremio_addons", "trakt_access_token", "trakt_refresh_token", "trakt_expires_at", "trakt_user", "sports_favorites", "series_calendar_cache")

    private fun allowed(): Boolean = runCatching { trustedPage() }.getOrDefault(false)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return gen.generateKey()
    }
    private fun encrypt(value: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        require(raw.size > 12)
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(body), Charsets.UTF_8)
    }

    @JavascriptInterface fun getSecure(name: String): String {
        if (!allowed() || name !in allowedKeys) return ""
        return runCatching { prefs.getString(name, null)?.let(::decrypt) ?: "" }.getOrDefault("")
    }
    @JavascriptInterface fun putSecure(name: String, value: String): Boolean {
        if (!allowed() || name !in allowedKeys || value.length > 200_000) return false
        return runCatching { prefs.edit().putString(name, encrypt(value)).commit() }.getOrDefault(false)
    }
    @JavascriptInterface fun removeSecure(name: String): Boolean {
        if (!allowed() || name !in allowedKeys) return false
        return runCatching { prefs.edit().remove(name).commit() }.getOrDefault(false)
    }
    @JavascriptInterface fun isTvDevice(): Boolean = allowed() && runCatching { tvDevice() }.getOrDefault(false)
    @JavascriptInterface fun nativeDownload(url: String, fileName: String, mimeType: String): Long {
        if (!allowed() || !url.startsWith("https://", true)) return -1L
        return runCatching {
            val uri = Uri.parse(url)
            require(!uri.host.isNullOrBlank())
            val clean = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "umit-download" }
            val req = DownloadManager.Request(uri)
                .setTitle(clean)
                .setDescription("Ümit TV indirme")
                .setMimeType(mimeType.take(100).ifBlank { "application/octet-stream" })
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, clean)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        }.getOrDefault(-1L)
    }

    /** ESPN scoreboard bridge: WebView CORS/network failures are bypassed natively. Only the public ESPN API host is allowed. */
    @JavascriptInterface fun fetchJson(url: String): String {
        if (!allowed()) return JSONObject().put("ok", false).put("error", "Güvenli sayfa doğrulanamadı").toString()
        var conn: HttpURLConnection? = null
        return try {
            val parsed = Uri.parse(url)
            require(parsed.scheme.equals("https", true) && parsed.host.equals("site.api.espn.com", true)) { "İzin verilmeyen veri kaynağı" }
            require(parsed.path.orEmpty().startsWith("/apis/site/v2/sports/") && parsed.path.orEmpty().endsWith("/scoreboard")) { "Yalnızca skor servisi kullanılabilir" }
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "UmitTV-Android/10.4")
                useCaches = true
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(body.length <= 2_000_000) { "Yanıt boyutu sınırı aşıldı" }
            if (code !in 200..299) JSONObject().put("ok", false).put("status", code).put("error", "ESPN HTTP $code").toString()
            else {
                JSONObject(body)
                JSONObject().put("ok", true).put("data", JSONObject(body)).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", (e.message ?: "Bağlantı hatası").take(240)).toString()
        } finally { conn?.disconnect() }
    }

    @JavascriptInterface fun nativeInfo(): String {
        if (!allowed()) return "{}"
        return JSONObject().put("tv", runCatching { tvDevice() }.getOrDefault(false))
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("manufacturer", android.os.Build.MANUFACTURER ?: "")
            .put("model", android.os.Build.MODEL ?: "").toString()
    }
}
