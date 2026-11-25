package com.umitm0d.tvizle

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vavoo HLS için KESİNTİSİZ yerel proxy (v9.6).
 *
 * ExoPlayer yalnızca sabit yerel adresleri görür:
 *   /root.m3u8       → kök playlist (master ya da doğrudan media playlist)
 *   /p/v<n>.m3u8     → master içindeki n. varyant (sabit takma ad; upstream adresi değişse de aynı kalır)
 *   /p/m<n>.m3u8     → master içindeki n. ses/altyazı/I-frame playlist'i
 *   /u/<base64>      → segment, anahtar, init parçası
 *
 * Eski sürümde yalnızca kök adres güncelleniyordu; ExoPlayer'ın yoklamakta olduğu alt playlist ve segment
 * adresleri eski imzada kalıyor, imza dolunca 403 gelip oynatıcı baştan kuruluyordu ("yenileme").
 * Burada:
 *  - alt playlist adresleri sabit takma adlarla verilir, gerçek adres proxy içinde tutulur;
 *  - upstream 401/403/404/410 verirse proxy kendi içinde adresi yeniler (hazır bekleyen adres varsa anında),
 *    isteği yeni adresle tekrarlar; ExoPlayer hatayı hiç görmez;
 *  - süresi dolmuş imzalı segment adresi, güncel playlist'teki aynı dosya adıyla yeni adrese çevrilir;
 *  - kısa ağ kopmalarında (zaman aşımı / 5xx) hızlı tekrar denenir, olmazsa son iyi playlist bir süre sunulur;
 *  - upstream bağlantıları havuzda tutulur (her istekte yeni TLS el sıkışması yok → zayıf cihazda gecikme azalır).
 */
class VavooHlsProxy private constructor(
    initialUrl: String,
    private val ua: String,
    private val referer: String,
    private val renewer: (() -> String?)?
) {
    @Volatile private var originUrl: String = initialUrl
    @Volatile private var standbyUrl: String? = null
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val localBase = "http://127.0.0.1:${server.localPort}"
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "VavooHlsProxy-Worker").apply { isDaemon = true } }
    private val acceptThread = Thread({ acceptLoop() }, "VavooHlsProxy-Accept").apply { isDaemon = true }

    // "v0","m1" → mutlak upstream playlist adresi (master'dan)
    private val variants = ConcurrentHashMap<String, String>()
    // segment dosya adı → hangi playlist anahtarından geldi (süresi dolan segment adresini yeniden bulmak için)
    private val segOwner = ConcurrentHashMap<String, String>()
    private class Cached(val body: ByteArray, val at: Long)
    private val lastPlaylist = ConcurrentHashMap<String, Cached>()
    private val renewLock = Any()
    @Volatile private var lastRenewAt = 0L

    val localUrl: String = "$localBase/root.m3u8"

    init { acceptThread.start() }

    /** Yeni oturum: kök adres değişir, öğrenilmiş alt playlist/önbellek temizlenir. */
    fun updateOrigin(url: String) {
        if (closed.get() || !url.startsWith("http", true)) return
        originUrl = url
        standbyUrl = null
        variants.clear(); segOwner.clear(); lastPlaylist.clear()
    }

    /**
     * Hazır bekleyen yeni adres. Oynatma SIRASINDA KULLANILMAZ (canlı akışı bozmaz);
     * yalnızca upstream hata verdiğinde anında devreye girer.
     */
    fun setStandby(url: String) {
        if (!closed.get() && url.startsWith("http", true) && url != originUrl) standbyUrl = url
    }

    /** Dışarıdan (oynatıcı hata verdiğinde) zorla yenileme. Arka plan iş parçacığından çağırın. */
    fun forceRenew(): Boolean { lastRenewAt = 0L; return renew() }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val socket = server.accept()
                socket.tcpNoDelay = true
                socket.soTimeout = 60_000
                workers.execute { handle(socket) }
            } catch (_: Throwable) {
                if (!closed.get()) continue else break
            }
        }
    }

    // ───────────── İstek işleme ─────────────

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) { respond(output, 400, "Bad Request", ByteArray(0)); return }
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val ix = line.indexOf(':')
                    if (ix > 0) headers[line.substring(0, ix).trim().lowercase()] = line.substring(ix + 1).trim()
                }
                if (method != "GET" && method != "HEAD") { respond(output, 405, "Method Not Allowed", ByteArray(0)); return }

                when {
                    path == "/root.m3u8" -> servePlaylist("root", method, output)
                    path.startsWith("/p/") -> servePlaylist(path.removePrefix("/p/").removeSuffix(".m3u8"), method, output)
                    path.startsWith("/u/") -> {
                        val target = decodeUrl(path.removePrefix("/u/"))
                        if (target.isNullOrBlank() || !target.startsWith("http", true)) respond(output, 404, "Not Found", ByteArray(0))
                        else serveSegment(method, target, headers, output)
                    }
                    else -> respond(output, 404, "Not Found", ByteArray(0))
                }
            } catch (_: Throwable) {
                runCatching { respond(BufferedOutputStream(client.getOutputStream()), 502, "Bad Gateway", ByteArray(0)) }
            }
        }
    }

    // ───────────── Playlist ─────────────

    private class PlRes(val code: Int, val text: String?, val finalUrl: String)

    private fun targetFor(key: String): String? {
        if (key == "root") return originUrl
        variants[key]?.let { return it }
        // alt playlist adresi henüz öğrenilmediyse (ör. yeni oturum) master'ı bir kez çekip öğren
        val r = fetchPlaylist(originUrl)
        val t = r.text
        if (t != null && t.contains("#EXT-X-STREAM-INF")) rewriteMaster(t, r.finalUrl)
        return variants[key]
    }

    private fun fetchPlaylist(target: String): PlRes {
        val u = open(target, "GET", emptyMap()) ?: return PlRes(0, null, target)
        return try {
            if (u.code !in 200..299 || u.stream == null) {
                val code = u.code
                release(u, false)
                PlRes(code, null, u.finalUrl)
            } else {
                val body = readLimited(BufferedInputStream(u.stream), 2 * 1024 * 1024)
                release(u, true)
                PlRes(u.code, body.toString(Charsets.UTF_8), u.finalUrl)
            }
        } catch (_: Throwable) {
            release(u, false)
            PlRes(0, null, target)
        }
    }

    private fun servePlaylist(key: String, method: String, out: BufferedOutputStream) {
        var renewed = false
        var lastCode = 0
        for (attempt in 0 until 4) {
            val target = targetFor(key) ?: break
            val r = fetchPlaylist(target)
            lastCode = r.code
            val text = r.text
            if (text != null && text.trimStart().startsWith("#EXTM3U")) {
                val body = (if (text.contains("#EXT-X-STREAM-INF")) rewriteMaster(text, r.finalUrl) else rewriteMedia(key, text, r.finalUrl))
                    .toByteArray(Charsets.UTF_8)
                lastPlaylist[key] = Cached(body, System.currentTimeMillis())
                sendPlaylist(out, method, body)
                return
            }
            if (!renewed && r.code in AUTH_CODES) {
                // imza/adres bayatladı: proxy içinde yenile, ExoPlayer hatayı görmesin
                renewed = true
                renew()
                continue
            }
            if (r.code == 0 || r.code >= 500 || r.code == 429 || r.code in AUTH_CODES) {
                try { Thread.sleep(300L * (attempt + 1)) } catch (_: InterruptedException) { return }
                continue
            }
            break
        }
        // Upstream şu an cevap vermiyor: son iyi playlist'i kısa süre sun (oynatıcı tamponla devam eder, tekrar yoklar)
        val stale = lastPlaylist[key]
        if (stale != null && System.currentTimeMillis() - stale.at < STALE_PLAYLIST_MS) {
            sendPlaylist(out, method, stale.body)
            return
        }
        val code = if (lastCode in 400..599) lastCode else 502
        respond(out, code, reason(code), ByteArray(0))
    }

    private fun sendPlaylist(out: BufferedOutputStream, method: String, body: ByteArray) {
        writeHeaders(out, 200, "OK", "application/vnd.apple.mpegurl", body.size.toLong(), null)
        if (method != "HEAD") out.write(body)
        out.flush()
    }

    private fun resolveUri(base: String, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("data:", true) || t.startsWith("skd:", true)) return null
        return try {
            val abs = URI(base).resolve(t).toString()
            if (abs.startsWith("http://", true) || abs.startsWith("https://", true)) abs else null
        } catch (_: Throwable) { null }
    }

    private fun proxyAbs(base: String, raw: String): String {
        val abs = resolveUri(base, raw) ?: return raw
        return "$localBase/u/" + encodeUrl(abs)
    }

    /** Master: varyant ve ses/altyazı playlist adreslerini sabit takma adlara çevirir, gerçek adresleri kaydeder. */
    private fun rewriteMaster(body: String, base: String): String {
        var vi = 0
        var mi = 0
        var expectVariant = false
        val sb = StringBuilder(body.length + 256)
        for (line in body.lineSequence()) {
            val t = line.trim()
            when {
                t.isEmpty() -> sb.append(line)
                t.startsWith("#EXT-X-STREAM-INF") -> { expectVariant = true; sb.append(line) }
                t.startsWith("#") -> {
                    val playlistTag = t.startsWith("#EXT-X-MEDIA:") || t.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    sb.append(URI_ATTR.replace(line) { m ->
                        val raw = m.groupValues[1]
                        if (playlistTag) {
                            val abs = resolveUri(base, raw)
                            if (abs == null) m.value else {
                                val k = "m" + (mi++)
                                variants[k] = abs
                                "URI=\"$localBase/p/$k.m3u8\""
                            }
                        } else "URI=\"" + proxyAbs(base, raw) + "\""
                    })
                }
                expectVariant -> {
                    expectVariant = false
                    val abs = resolveUri(base, t)
                    if (abs == null) sb.append(line) else {
                        val k = "v" + (vi++)
                        variants[k] = abs
                        sb.append("$localBase/p/$k.m3u8")
                    }
                }
                else -> sb.append(proxyAbs(base, t))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Media playlist: segment/anahtar/init adresleri /u/ üzerinden geçer; segment → playlist eşlemesi tutulur. */
    private fun rewriteMedia(key: String, body: String, base: String): String {
        if (segOwner.size > 4000) segOwner.clear()
        val sb = StringBuilder(body.length + 512)
        for (line in body.lineSequence()) {
            val t = line.trim()
            when {
                t.isEmpty() -> sb.append(line)
                t.startsWith("#") -> sb.append(URI_ATTR.replace(line) { m -> "URI=\"" + proxyAbs(base, m.groupValues[1]) + "\"" })
                else -> {
                    resolveUri(base, t)?.let { segOwner[baseName(it)] = key }
                    sb.append(proxyAbs(base, t))
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ───────────── Adres yenileme ─────────────

    /** Hazır bekleyen adres varsa onu, yoksa renewer ile yeni adresi alıp kök adresi ve alt playlist eşlemesini günceller. */
    private fun renew(): Boolean {
        return synchronized(renewLock) renew@{
            val now = System.currentTimeMillis()
            if (now - lastRenewAt < RENEW_MIN_GAP_MS) return@renew true   // az önce yenilendi; yeni adresle tekrar denensin
            lastRenewAt = now
            var fresh = standbyUrl
            standbyUrl = null
            if (fresh == null) fresh = try { renewer?.invoke() } catch (_: Throwable) { null }
            if (fresh.isNullOrBlank() || !fresh.startsWith("http", true)) return@renew false
            originUrl = fresh
            val r = fetchPlaylist(fresh)
            val t = r.text
            if (t != null && t.contains("#EXT-X-STREAM-INF")) rewriteMaster(t, r.finalUrl)
            true
        }
    }

    // ───────────── Segment / anahtar ─────────────

    private fun serveSegment(method: String, target: String, reqHeaders: Map<String, String>, out: BufferedOutputStream) {
        var cur = target
        var rebased = false
        var up: Up? = null
        var failed: Up? = null
        for (attempt in 0 until 3) {
            val u = open(cur, method, reqHeaders)
            if (u != null && u.code < 400) { up = u; break }
            val code = u?.code ?: 0
            if (!rebased && code in AUTH_CODES) {
                // imzalı segment adresi dolmuş: aynı dosyayı güncel playlist'ten yeni adresle bul
                rebased = true
                failed?.let { release(it, false) }
                failed = u
                val alt = rebaseSegment(cur)
                if (alt != null && alt != cur) { cur = alt; continue }
                break
            }
            if (code == 0 || code >= 500) {
                u?.let { release(it, false) }
                try { Thread.sleep(250L * (attempt + 1)) } catch (_: InterruptedException) { return }
                continue
            }
            failed?.let { release(it, false) }
            failed = u
            break
        }
        val ok = up
        if (ok == null) {
            val f = failed
            val code = f?.code ?: 502
            f?.let { release(it, false) }
            respond(out, code, reason(code), ByteArray(0))
            return
        }
        failed?.let { release(it, false) }
        var drained = false
        try {
            val length = ok.len
            writeHeaders(out, ok.code, reason(ok.code), ok.type ?: "application/octet-stream", length,
                if (length < 0 && method != "HEAD") "chunked" else null, ok.range)
            if (method == "HEAD") { drained = true; return }
            val stream = ok.stream ?: run { drained = true; return }
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (length < 0) {
                    out.write(Integer.toHexString(n).toByteArray(Charsets.US_ASCII))
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(buffer, 0, n)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                } else out.write(buffer, 0, n)
                out.flush()
            }
            if (length < 0) { out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII)); out.flush() }
            drained = true
        } finally {
            release(ok, drained)
        }
    }

    private fun rebaseSegment(oldUrl: String): String? {
        val name = baseName(oldUrl)
        val key = segOwner[name] ?: return null
        renew()
        val pl = targetFor(key) ?: return null
        val r = fetchPlaylist(pl)
        val text = r.text ?: return null
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val abs = resolveUri(r.finalUrl, t) ?: continue
            if (baseName(abs) == name) return abs
        }
        return null
    }

    // ───────────── Upstream bağlantısı ─────────────

    private class Up(
        val code: Int, val type: String?, val len: Long, val range: String?,
        val stream: InputStream?, val finalUrl: String, val conn: HttpURLConnection
    )

    private fun open(target: String, method: String, reqHeaders: Map<String, String>): Up? {
        return try {
            val c = URL(target).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua.ifBlank { ExoConfig.DEFAULT_UA })
            if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            reqHeaders["range"]?.let { c.setRequestProperty("Range", it) }
            reqHeaders["if-range"]?.let { c.setRequestProperty("If-Range", it) }
            val code = c.responseCode
            val stream = if (method == "HEAD") null else if (code >= 400) c.errorStream else c.inputStream
            Up(code, c.contentType, c.contentLengthLong, c.getHeaderField("Content-Range"), stream, c.url.toString(), c)
        } catch (_: Throwable) { null }
    }

    /** Tamamen okunduysa bağlantı havuza döner (yeniden TLS yok); yarıda kaldıysa kapatılır. */
    private fun release(u: Up, drained: Boolean) {
        if (drained) runCatching { u.stream?.close() } else runCatching { u.conn.disconnect() }
    }

    // ───────────── Yardımcılar ─────────────

    private fun baseName(url: String): String = url.substringBefore('?').substringBefore('#').substringAfterLast('/')

    private fun encodeUrl(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeUrl(value: String): String? = try {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    } catch (_: Throwable) { null }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == 10) break
            if (b != 13 && bytes.size() < 8192) bytes.write(b)
        }
        return bytes.toString("UTF-8")
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun respond(out: BufferedOutputStream, code: Int, message: String, body: ByteArray) {
        writeHeaders(out, code, message, "text/plain; charset=utf-8", body.size.toLong(), null)
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun writeHeaders(out: BufferedOutputStream, code: Int, message: String, type: String, length: Long, transfer: String?, contentRange: String? = null) {
        out.write("HTTP/1.1 $code $message\r\n".toByteArray(Charsets.US_ASCII))
        out.write("Content-Type: $type\r\n".toByteArray(Charsets.US_ASCII))
        if (transfer != null) out.write("Transfer-Encoding: $transfer\r\n".toByteArray(Charsets.US_ASCII))
        else out.write("Content-Length: ${length.coerceAtLeast(0)}\r\n".toByteArray(Charsets.US_ASCII))
        if (!contentRange.isNullOrBlank()) out.write("Content-Range: $contentRange\r\n".toByteArray(Charsets.US_ASCII))
        out.write("Accept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        410 -> "Gone"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
        502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "Upstream Response"
    }

    companion object {
        private val URI_ATTR = Regex("URI=\"([^\"]+)\"")
        private val AUTH_CODES = setOf(401, 403, 404, 410)
        private const val STALE_PLAYLIST_MS = 30_000L   // upstream yokken son iyi playlist bu süre sunulur
        private const val RENEW_MIN_GAP_MS = 4_000L     // art arda yenileme fırtınasını önler

        /** [renewer]: adres bayatlayınca ARKA PLANDA çağrılır; yeni çözülmüş yayın adresini (ya da null) döndürür. */
        fun create(initialUrl: String, ua: String, referer: String, renewer: (() -> String?)? = null): VavooHlsProxy? = try {
            VavooHlsProxy(initialUrl, ua, referer, renewer)
        } catch (_: Throwable) { null }
    }
}
