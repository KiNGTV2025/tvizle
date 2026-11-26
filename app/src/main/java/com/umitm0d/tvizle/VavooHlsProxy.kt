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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vavoo HLS için tek ve sabit yerel URL.
 *
 * PlaylistRefresh: Yeni imzalı URL geldiğinde kök playlist arka planda okunur ve
 * master playlist'teki kalite/audio yolları aynı yerel route kimliklerine bağlanır.
 * ExoPlayer'ın açık MediaSource'u ve buffer'ı değiştirilmez.
 *
 * PlaylistKeepAlive: Aktif media playlist düşük frekansta arka planda tazelenir.
 * Precache: Son playlist kısa süre bellekte tutulur; ExoPlayer hemen ardından aynı
 * listeyi isterse ikinci bir ağ isteği yerine cache kullanılır. Segmentler cache'lenmez.
 */
class VavooHlsProxy private constructor(
    initialUrl: String,
    private val ua: String,
    private val referer: String
) {
    @Volatile private var originUrl: String = initialUrl
    @Volatile private var activeRoute: String = "/root.m3u8"
    private val closed = AtomicBoolean(false)
    private val routeTargets = ConcurrentHashMap<String, String>()
    private data class PlaylistCache(val body: ByteArray, val savedAt: Long)
    private val playlistCache = ConcurrentHashMap<String, PlaylistCache>()

    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(3) { r -> Thread(r, "VavooHlsProxy-Worker").apply { isDaemon = true } }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VavooHlsProxy-KeepAlive").apply { isDaemon = true }
    }
    private val acceptThread = Thread({ acceptLoop() }, "VavooHlsProxy-Accept").apply { isDaemon = true }

    val localUrl: String = "http://127.0.0.1:${server.localPort}/root.m3u8"

    init {
        acceptThread.start()
        scheduler.scheduleWithFixedDelay({ keepAliveAndPrecache() }, 12, 22, TimeUnit.SECONDS)
        workers.execute { refreshRootMappings(initialUrl) }
    }

    fun updateOrigin(url: String) {
        if (closed.get() || !url.startsWith("http", true)) return
        originUrl = url
        playlistCache.clear()
        // Yeni master playlist'i önceden oku; seçili kalite yolunun yerel kimliği değişmez.
        workers.execute { refreshRootMappings(url) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { scheduler.shutdownNow() }
        runCatching { server.close() }
        runCatching { workers.shutdownNow() }
        routeTargets.clear()
        playlistCache.clear()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val socket = server.accept()
                socket.tcpNoDelay = true
                socket.soTimeout = 25_000
                workers.execute { handle(socket) }
            } catch (_: Throwable) {
                if (!closed.get()) continue else break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val output = BufferedOutputStream(client.getOutputStream())
            var requestPath = ""
            var responseStarted = false
            try {
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) { respond(output, 400, "Bad Request", ByteArray(0)); return }
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                requestPath = path
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val ix = line.indexOf(':')
                    if (ix > 0) headers[line.substring(0, ix).trim().lowercase()] = line.substring(ix + 1).trim()
                }
                if (method != "GET" && method != "HEAD") { respond(output, 405, "Method Not Allowed", ByteArray(0)); return }
                // KeepAlive/Precache tarafından son 1.5 saniyede alınan playlist'i doğrudan sun.
                if (method == "GET") {
                    val warm = playlistCache[path]
                    if (warm != null && System.currentTimeMillis() - warm.savedAt <= 1_500L) {
                        writeHeaders(output, 200, "OK", "application/vnd.apple.mpegurl", warm.body.size.toLong(), null)
                        responseStarted = true
                        output.write(warm.body)
                        output.flush()
                        return
                    }
                }

                val target = when {
                    path == "/root.m3u8" -> originUrl
                    path.startsWith("/r/") -> routeTargets[path.removePrefix("/r/")]
                    path.startsWith("/u/") -> decodeUrl(path.removePrefix("/u/"))
                    else -> null
                }
                if (target.isNullOrBlank() || !target.startsWith("http", true)) {
                    respond(output, 404, "Not Found", ByteArray(0)); return
                }
                if (path == "/root.m3u8" || path.startsWith("/r/")) activeRoute = path

                val upstream = openUpstream(target, method, headers, 8_000, 20_000)
                try {
                    val code = upstream.responseCode
                    if (code !in 200..299) {
                        val cached = playlistCache[path]
                        if (cached != null && System.currentTimeMillis() - cached.savedAt <= 7_000L) {
                            writeHeaders(output, 200, "OK", "application/vnd.apple.mpegurl", cached.body.size.toLong(), null)
                            responseStarted = true
                            if (method != "HEAD") output.write(cached.body)
                            output.flush()
                            return
                        }
                    }
                    val stream = if (code >= 400) upstream.errorStream else upstream.inputStream
                    if (method == "HEAD") {
                        writeHeaders(output, code, reason(code), upstream.contentType ?: "application/octet-stream", upstream.contentLengthLong, null, upstream.getHeaderField("Content-Range"))
                        responseStarted = true
                        return
                    }
                    if (stream == null) { respond(output, code, reason(code), ByteArray(0)); return }
                    val buffered = BufferedInputStream(stream)
                    buffered.mark(16)
                    val prefix = ByteArray(7)
                    val prefixCount = buffered.read(prefix)
                    buffered.reset()
                    val startsPlaylist = prefixCount > 0 && String(prefix, 0, prefixCount, Charsets.UTF_8).trimStart().startsWith("#EXTM3U")
                    val isPlaylist = startsPlaylist || (upstream.contentType ?: "").contains("mpegurl", true) ||
                        (upstream.contentType ?: "").contains("m3u", true) || target.substringBefore('?').endsWith(".m3u8", true)

                    if (isPlaylist) {
                        val raw = readLimited(buffered, 2 * 1024 * 1024)
                        val text = raw.toString(Charsets.UTF_8)
                        if (text.trimStart().startsWith("#EXTM3U")) {
                            val rewritten = rewritePlaylist(text, upstream.url.toString(), path).toByteArray(Charsets.UTF_8)
                            cachePlaylist(path, rewritten)
                            writeHeaders(output, code, reason(code), "application/vnd.apple.mpegurl", rewritten.size.toLong(), null)
                            responseStarted = true
                            output.write(rewritten)
                            output.flush()
                        } else {
                            writeHeaders(output, code, reason(code), upstream.contentType ?: "application/octet-stream", raw.size.toLong(), null)
                            responseStarted = true
                            output.write(raw); output.flush()
                        }
                    } else {
                        val length = upstream.contentLengthLong
                        val type = upstream.contentType ?: "application/octet-stream"
                        writeHeaders(output, code, reason(code), type, length, if (length < 0) "chunked" else null, upstream.getHeaderField("Content-Range"))
                        responseStarted = true
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = buffered.read(buffer)
                            if (n < 0) break
                            if (length < 0) {
                                output.write(Integer.toHexString(n).toByteArray(Charsets.US_ASCII))
                                output.write("\r\n".toByteArray(Charsets.US_ASCII))
                                output.write(buffer, 0, n)
                                output.write("\r\n".toByteArray(Charsets.US_ASCII))
                            } else output.write(buffer, 0, n)
                            output.flush()
                        }
                        if (length < 0) { output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush() }
                        Unit
                    }
                } finally { upstream.disconnect() }
            } catch (_: Throwable) {
                if (responseStarted) return@use
                val cached = playlistCache[requestPath]
                if (cached != null && System.currentTimeMillis() - cached.savedAt <= 7_000L) {
                    runCatching {
                        writeHeaders(output, 200, "OK", "application/vnd.apple.mpegurl", cached.body.size.toLong(), null)
                        output.write(cached.body)
                        output.flush()
                    }
                } else {
                    runCatching { respond(output, 502, "Bad Gateway", ByteArray(0)) }
                }
            }
        }
    }

    private fun openUpstream(
        target: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 4_000,
        readMs: Int = 6_000
    ): HttpURLConnection = (URL(target).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = connectMs
        readTimeout = readMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", ua.ifBlank { ExoConfig.DEFAULT_UA })
        if (referer.isNotBlank()) setRequestProperty("Referer", referer)
        setRequestProperty("Accept", "*/*")
        setRequestProperty("Accept-Encoding", "identity")
        headers["range"]?.let { setRequestProperty("Range", it) }
        headers["if-range"]?.let { setRequestProperty("If-Range", it) }
    }

    private fun rewritePlaylist(body: String, baseUrl: String, requestPath: String): String {
        val parentKey = if (requestPath == "/root.m3u8") "root" else requestPath.removePrefix("/r/").ifBlank { "root" }
        val master = body.contains("#EXT-X-STREAM-INF", true)
        var variantIndex = 0
        var audioIndex = 0
        var subtitleIndex = 0
        var pendingVariant = false

        fun absolute(raw: String): String? = try {
            val value = URI(baseUrl).resolve(raw.trim()).toString()
            value.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        } catch (_: Throwable) { null }

        fun resource(raw: String): String {
            val value = absolute(raw) ?: return raw
            return "http://127.0.0.1:${server.localPort}/u/" + encodeUrl(value)
        }
        fun route(raw: String, key: String): String {
            val value = absolute(raw) ?: return raw
            routeTargets[key] = value
            return "http://127.0.0.1:${server.localPort}/r/$key"
        }

        return body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#EXT-X-STREAM-INF", true) -> { pendingVariant = true; line }
                trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF", true) && master -> {
                    Regex("URI=\"([^\"]+)\"").replace(line) { m ->
                        val key = "$parentKey/iframe_${variantIndex++}"
                        "URI=\"${route(m.groupValues[1], key)}\""
                    }
                }
                trimmed.startsWith("#EXT-X-MEDIA", true) && master -> {
                    Regex("URI=\"([^\"]+)\"").replace(line) { m ->
                        val type = Regex("TYPE=([A-Z]+)").find(line)?.groupValues?.getOrNull(1)
                        val key = when (type) {
                            "AUDIO" -> "$parentKey/audio_${audioIndex++}"
                            "SUBTITLES" -> "$parentKey/sub_${subtitleIndex++}"
                            else -> "$parentKey/media_${audioIndex++}"
                        }
                        "URI=\"${route(m.groupValues[1], key)}\""
                    }
                }
                trimmed.startsWith("#") -> {
                    // KEY, MAP, SESSION-KEY vb. kaynakları da proxy'den geçir; URI'si olmayan etiketler değişmez.
                    Regex("URI=\"([^\"]+)\"").replace(line) { m -> "URI=\"${resource(m.groupValues[1])}\"" }
                }
                master && pendingVariant -> {
                    val key = "$parentKey/v${variantIndex++}"
                    pendingVariant = false
                    route(trimmed, key)
                }
                else -> resource(trimmed)
            }
        }
    }

    /** Yeni imzalı master playlist'i alıp seçili kalite route'larını günceller. */
    private fun refreshRootMappings(url: String) {
        if (closed.get()) return
        try {
            val c = openUpstream(url, "GET", connectMs = 4_000, readMs = 5_000)
            try {
                if (c.responseCode !in 200..299) return
                val input = c.inputStream
                val raw = input.use { readLimited(it, 2 * 1024 * 1024) }
                val text = raw.toString(Charsets.UTF_8)
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val rewritten = rewritePlaylist(text, c.url.toString(), "/root.m3u8").toByteArray(Charsets.UTF_8)
                    cachePlaylist("/root.m3u8", rewritten)
                }
            } finally { c.disconnect() }
        } catch (_: Throwable) { /* eski route eşlemeleri korunur */ }
    }

    /** PlaylistKeepAlive + hafif playlist pre-cache; medya segmentlerine ekstra istek atılmaz. */
    private fun keepAliveAndPrecache() {
        if (closed.get()) return
        val path = activeRoute
        val target = when {
            path == "/root.m3u8" -> originUrl
            path.startsWith("/r/") -> routeTargets[path.removePrefix("/r/")] ?: originUrl
            else -> originUrl
        }
        try {
            val c = openUpstream(target, "GET", connectMs = 3_000, readMs = 4_000)
            try {
                if (c.responseCode !in 200..299) return
                val raw = c.inputStream.use { readLimited(it, 2 * 1024 * 1024) }
                val text = raw.toString(Charsets.UTF_8)
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val rewritten = rewritePlaylist(text, c.url.toString(), path).toByteArray(Charsets.UTF_8)
                    cachePlaylist(path, rewritten)
                }
            } finally { c.disconnect() }
        } catch (_: Throwable) { /* KeepAlive başarısızlığı oynatımı etkilemez */ }
    }

    private fun cachePlaylist(path: String, body: ByteArray) {
        if (body.size > 2 * 1024 * 1024) return
        playlistCache[path] = PlaylistCache(body, System.currentTimeMillis())
        if (playlistCache.size > 8) {
            val oldest = playlistCache.entries.minByOrNull { it.value.savedAt }?.key
            if (oldest != null && oldest != path) playlistCache.remove(oldest)
        }
    }

    private fun encodeUrl(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
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
        fun create(initialUrl: String, ua: String, referer: String): VavooHlsProxy? = try {
            VavooHlsProxy(initialUrl, ua, referer)
        } catch (_: Throwable) { null }
    }
}
