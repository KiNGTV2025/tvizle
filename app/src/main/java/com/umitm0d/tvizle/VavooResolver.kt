package com.umitm0d.tvizle

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object VavooResolver {

    val REFERER: String get() = ObfuscatedText.get(0)
    val UA_APP: String get() = ObfuscatedText.get(28)
    private val UA_PING: String get() = ObfuscatedText.get(29)

    private const val TTL = 120_000L          // çözülmüş adres 2 dk geçerli
    private const val SIG_TTL = 900_000L      // imza 15 dk geçerli
    private const val SIG_BACKOFF = 30_000L   // imza alınamazsa 30 sn bekle

    private class Entry(val url: String, val exp: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile private var sig: String? = null
    @Volatile private var sigExp = 0L
    @Volatile private var sigBlockUntil = 0L

    private val PING_URLS = arrayOf(
        "https://www.vypn.net/api/app/ping",
        "https://cache.vypn.net/api/app/ping"
    )
    private val RESOLVE_URLS = arrayOf(
        ObfuscatedText.get(1),
        ObfuscatedText.get(2),
        ObfuscatedText.get(3),
        ObfuscatedText.get(4)
    )
    private val LIVE_BASES = arrayOf(
        ObfuscatedText.get(5),
        ObfuscatedText.get(6),
        ObfuscatedText.get(7),
        ObfuscatedText.get(8),
        ObfuscatedText.get(9)
    )
    private val HOST_MARKS = arrayOf(
        ObfuscatedText.get(10), ObfuscatedText.get(11), ObfuscatedText.get(12), ObfuscatedText.get(13), ObfuscatedText.get(14),
        ObfuscatedText.get(15), ObfuscatedText.get(16), ObfuscatedText.get(17), ObfuscatedText.get(18), ObfuscatedText.get(19)
    )

    // ───────────── Dışarıya açık API ─────────────

    /** Bu adres Vavoo adresi mi? */
    @JvmStatic
    fun matches(url: String?): Boolean {
        if (url == null) return false
        val l = url.lowercase(Locale.ROOT)
        return HOST_MARKS.any { l.contains(it) }
    }

    /** Süresi dolmamış çözülmüş adres varsa hemen döndürür (ağ isteği yok), yoksa null. */
    @JvmStatic
    fun cached(src: String): String? {
        val e = cache[src.trim()] ?: return null
        return if (System.currentTimeMillis() < e.exp) e.url else null
    }

    /** Önbelleği temizler (çözülen adres çalışmadıysa yeniden çözdürmek için). */
    @JvmStatic
    fun invalidate(src: String) {
        cache.remove(src.trim())
        clearSig()
    }

    /**
     * Arka plan iş parçacığında çözer; [done] sonucu aynı iş parçacığında çağırır (UI'ya kendiniz geçin).
     * Çözülemezse null verir.
     */
    @JvmStatic
    fun resolveAsync(src: String, done: (String?) -> Unit) {
        Thread {
            val r = try { resolveBlocking(src) } catch (_: Throwable) { null }
            done(r)
        }.start()
    }

    /**
     * İkinci aşama: yeni yayın adresinin erişilebilir olduğunu arka planda kontrol eder.
     * Sadece küçük bir aralık okunur; mevcut oynatıcıya dokunulmaz.
     */
    @JvmStatic
    fun preflightAsync(streamUrl: String, done: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                val c = open(streamUrl, "GET", 4500)
                c.setRequestProperty("User-Agent", UA_APP)
                c.setRequestProperty("Referer", REFERER)
                c.setRequestProperty("Range", "bytes=0-4095")
                c.setRequestProperty("Accept", "*/*")
                try {
                    val code = c.responseCode
                    if (code !in 200..206) false else {
                        val stream = if (code >= 400) c.errorStream else c.inputStream
                        val sample = stream?.use { input ->
                            val b = ByteArray(4096)
                            val n = input.read(b)
                            if (n > 0) String(b, 0, n, Charsets.UTF_8) else ""
                        } ?: ""
                        val head = sample.trimStart().take(80).lowercase(Locale.ROOT)
                        sample.isNotBlank() && !head.startsWith("<html") && !head.startsWith("<!doctype") && !head.startsWith("{\"error")
                    }
                } finally { c.disconnect() }
            } catch (_: Throwable) { false }
            done(ok)
        }.start()
    }

    /**
     * Oynarken token'ı (imzayı) canlı tutar (Vavoo uygulaması da ~4 dk'da bir yeniler). Yayın adresine DOKUNMAZ.
     * Yenileme başarısız olursa eski imza korunur. Ana iş parçacığında ÇAĞIRMAYIN.
     */
    @JvmStatic
    fun renewSigBlocking(): Boolean {
        val oldSig = sig
        val oldExp = sigExp
        sigExp = 0L
        sigBlockUntil = 0L
        val n = try { fetchSig() } catch (_: Throwable) { null }
        if (n == null) { sig = oldSig; sigExp = oldExp; return false }
        return true
    }

    /** Önbelleği atlayıp yayın adresini yeniden çözer; imzayı SİLMEZ (hızlı). Ana iş parçacığında ÇAĞIRMAYIN. */
    @JvmStatic
    fun resolveFreshBlocking(src: String): String? {
        cache.remove(src.trim())
        return resolveBlocking(src)
    }

    /** Engelleyici çözüm (ana iş parçacığında ÇAĞIRMAYIN). Başarılıysa önbelleğe de yazar. */
    @JvmStatic
    fun resolveBlocking(src: String): String? {
        val key = src.trim()
        cached(key)?.let { return it }
        val r = try { resolve(key) } catch (_: Throwable) { null }
        if (r != null) cache[key] = Entry(r, System.currentTimeMillis() + TTL)
        return r
    }

    // ───────────── İç işler ─────────────

    private fun cachedSig(): String? {
        val s = sig
        return if (s != null && System.currentTimeMillis() < sigExp) s else null
    }

    private fun clearSig() {
        sig = null
        sigExp = 0L
    }

    private fun first(vararg v: String?): String? {
        for (s in v) {
            if (s != null && s.trim().isNotEmpty() && s != "null") return s.trim()
        }
        return null
    }

    private fun extractUrl(s: String): String? {
        return try {
            if (s.startsWith("[")) {
                val arr = JSONArray(s)
                if (arr.length() == 0) return null
                val o = arr.optJSONObject(0) ?: return null
                first(o.optString("url"))
            } else if (s.startsWith("{")) {
                val o = JSONObject(s)
                val top = o.optString("url")
                val dataObj = o.optJSONObject("data")?.optString("url")
                val dataArr = o.optJSONArray("data")?.optJSONObject(0)?.optString("url")
                val js = o.optJSONObject("js")?.optString("url")
                first(top, dataObj, dataArr, js)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun open(url: String, method: String, timeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.instanceFollowRedirects = true
        return c
    }

    /** Gövdeyi (varsa) gönderir, 2xx ise yanıt metnini döndürür; aksi halde null. */
    private fun send(c: HttpURLConnection, body: String?): String? {
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code < 200 || code >= 300) return null
            val ins = c.inputStream ?: return null
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(ins, "UTF-8")).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    sb.append(line).append('\n')
                }
            }
            return sb.toString()
        } finally {
            c.disconnect()
        }
    }

    private fun pingBody(now: Long): String {
        val device = JSONObject()
            .put("type", "desktop")
            .put("uniqueId", UUID.randomUUID().toString())
        val os = JSONObject()
            .put("name", "win32")
            .put("version", "Windows 10 Pro")
            .put("abis", JSONArray().put("x64"))
            .put("host", "Lenovo")
        val app = JSONObject().put("platform", "electron")
        val version = JSONObject()
            .put("package", "net.vypn.app")
            .put("binary", "3.1.0")
            .put("js", "3.1.0")
        val metadata = JSONObject()
            .put("device", device)
            .put("os", os)
            .put("app", app)
            .put("version", version)
        val proxy = JSONObject()
            .put("supported", JSONArray().put("ss"))
            .put("engine", "Mu")
            .put("enabled", false)
            .put("autoServer", true)
        val iap = JSONObject().put("supported", false)
        return JSONObject()
            .put("reason", "app-focus")
            .put("locale", "en")
            .put("theme", "dark")
            .put("metadata", metadata)
            .put("appFocusTime", 0)
            .put("playerActive", false)
            .put("playDuration", 0)
            .put("devMode", false)
            .put("hasAddon", true)
            .put("castConnected", false)
            .put("package", "net.vypn.app")
            .put("version", "3.1.0")
            .put("process", "app")
            .put("firstAppStart", now)
            .put("lastAppStart", now)
            .put("ipLocation", JSONObject.NULL)
            .put("adblockEnabled", true)
            .put("proxy", proxy)
            .put("iap", iap)
            .toString()
    }

    @Synchronized
    private fun fetchSig(): String? {
        val now = System.currentTimeMillis()
        cachedSig()?.let { return it }
        if (now < sigBlockUntil) return null
        val body = pingBody(now)
        for (u in PING_URLS) {
            for (attempt in 0 until 2) {
                try {
                    val c = open(u, "POST", 8000)
                    c.setRequestProperty("User-Agent", UA_PING)
                    c.setRequestProperty("Accept", "*/*")
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val r = send(c, body)
                    if (r == null || r.trim().isEmpty()) continue
                    val j = JSONObject(r)
                    val s = first(
                        j.optString("sig"), j.optString("addonSig"), j.optString("signature"),
                        j.optString(ObfuscatedText.get(20)), j.optString(ObfuscatedText.get(21)),
                        j.optString("token")
                    )
                    if (s != null) {
                        sig = s
                        sigExp = System.currentTimeMillis() + SIG_TTL
                        return s
                    }
                } catch (_: Throwable) {
                    // sıradaki denemeye geç
                }
            }
        }
        sigBlockUntil = System.currentTimeMillis() + SIG_BACKOFF
        return null
    }

    private fun postResolve(endpoint: String, url: String, signature: String?): String? {
        return try {
            val body = JSONObject()
                .put("language", "de")
                .put("region", "AT")
                .put("url", url)
                .put("clientVersion", "3.1.4")
                .toString()
            val c = open(endpoint, "POST", 10000)
            c.setRequestProperty("User-Agent", UA_PING)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", "de")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!signature.isNullOrEmpty()) c.setRequestProperty(ObfuscatedText.get(21), signature)
            val r = send(c, body)
            if (r == null || r.trim().isEmpty()) null else r.trim()
        } catch (_: Throwable) {
            null
        }
    }

    /** Yönlendirmeleri izler; sayfa HTML dönerse içinden .m3u8 / yayın adresi ayıklar. */
    private fun finalUrl(url: String, signature: String?): String {
        val low = url.lowercase(Locale.ROOT)
        if (low.contains(".m3u8") || low.contains(".ts") || low.contains("/hls/")) return url
        return try {
            val head = open(url, "HEAD", 10000)
            head.setRequestProperty("User-Agent", UA_APP)
            head.setRequestProperty("Accept", "*/*")
            head.setRequestProperty("Connection", "close")
            if (signature != null) head.setRequestProperty(ObfuscatedText.get(21), signature)
            head.responseCode
            val finalU = head.url.toString()
            val ct = head.contentType
            head.disconnect()
            if (ct != null && ct.lowercase(Locale.ROOT).contains("text/html")) {
                val get = open(finalU, "GET", 10000)
                get.setRequestProperty("User-Agent", UA_APP)
                get.setRequestProperty("Accept", "*/*")
                val html = send(get, null)
                if (html != null) {
                    val m1 = Regex("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", RegexOption.IGNORE_CASE).find(html)
                    if (m1 != null) return m1.groupValues[1]
                    val m2 = Regex("(https?://[^\\s\"'<>]+(?:\\.ts|/live/|/stream/|/playlist|/index)[^\\s\"'<>]*)", RegexOption.IGNORE_CASE).find(html)
                    if (m2 != null) return m2.groupValues[1]
                }
            }
            finalU
        } catch (_: Throwable) {
            url
        }
    }

    private fun liveResolve(src: String): String? {
        val enc = URLEncoder.encode(src, "UTF-8")
        for (b in LIVE_BASES) {
            try {
                val c = open("$b/live/resolve?url=$enc", "GET", 10000)
                c.setRequestProperty("Accept", "application/json")
                val r = send(c, null)
                if (r != null) {
                    val u = extractUrl(r)
                    if (u != null) return u
                }
            } catch (_: Throwable) {
                // sıradaki sunucu
            }
        }
        return null
    }

    private fun resolve(src: String): String? {
        if (src.lowercase(Locale.ROOT).contains(ObfuscatedText.get(19))) return liveResolve(src)
        var p = src
        if (!p.contains(ObfuscatedText.get(18))) {
            val i = p.indexOf("/play/")
            if (i != -1) p = ObfuscatedText.get(22) + p.substring(i + 6)
        }
        val s = cachedSig() ?: fetchSig()
        for (ep in RESOLVE_URLS) {
            var r = postResolve(ep, p, s)
            if (r == null) r = postResolve(ep, p, null)
            if (r != null) {
                val u = extractUrl(r)
                if (u != null) return finalUrl(u, s)
            }
        }
        return null
    }
}
