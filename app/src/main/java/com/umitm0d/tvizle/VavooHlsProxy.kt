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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vavoo HLS için sabit yerel URL.
 * ExoPlayer aynı MediaSource'u oynatmaya devam eder; Vavoo imzalı adresi değişince
 * yalnızca kök playlist'in arka uç adresi güncellenir. Playlist/segment/key URL'leri
 * yerel proxy üzerinden geçirilir ve playlist URI'leri mutlak upstream URL'lere çevrilir.
 */
class VavooHlsProxy private constructor(initialUrl: String, private val ua: String, private val referer: String) {
    @Volatile private var originUrl: String = initialUrl
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "VavooHlsProxy-Worker").apply { isDaemon = true } }
    private val acceptThread = Thread({ acceptLoop() }, "VavooHlsProxy-Accept").apply { isDaemon = true }

    val localUrl: String = "http://127.0.0.1:${server.localPort}/root.m3u8"

    init { acceptThread.start() }

    fun updateOrigin(url: String) {
        if (!closed.get() && url.startsWith("http", true)) originUrl = url
    }

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
                socket.soTimeout = 25_000
                workers.execute { handle(socket) }
            } catch (_: Throwable) {
                if (!closed.get()) continue else break
            }
        }
    }

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

                val target = when {
                    path == "/root.m3u8" -> originUrl
                    path.startsWith("/u/") -> decodeUrl(path.removePrefix("/u/"))
                    else -> null
                }
                if (target.isNullOrBlank() || !target.startsWith("http", true)) {
                    respond(output, 404, "Not Found", ByteArray(0)); return
                }

                val upstream = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 8000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", ua.ifBlank { ExoConfig.DEFAULT_UA })
                    if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    headers["range"]?.let { setRequestProperty("Range", it) }
                    headers["if-range"]?.let { setRequestProperty("If-Range", it) }
                }
                try {
                    val code = upstream.responseCode
                    val stream = if (code >= 400) upstream.errorStream else upstream.inputStream
                    if (method == "HEAD") {
                        writeHeaders(output, code, reason(code), upstream.contentType ?: "application/octet-stream", upstream.contentLengthLong, null, upstream.getHeaderField("Content-Range"))
                        return
                    }
                    if (stream == null) {
                        respond(output, code, reason(code), ByteArray(0)); return
                    }
                    val bufferedStream = BufferedInputStream(stream)
                    bufferedStream.mark(16)
                    val prefix = ByteArray(7)
                    val prefixCount = bufferedStream.read(prefix)
                    bufferedStream.reset()
                    val startsAsPlaylist = prefixCount > 0 && String(prefix, 0, prefixCount, Charsets.UTF_8).trimStart().startsWith("#EXTM3U")
                    val isPlaylist = startsAsPlaylist || (upstream.contentType ?: "").contains("mpegurl", true) ||
                        (upstream.contentType ?: "").contains("m3u", true) || target.substringBefore('?').endsWith(".m3u8", true)
                    if (isPlaylist) {
                        val body = readLimited(bufferedStream, 2 * 1024 * 1024)
                        val text = body.toString(Charsets.UTF_8)
                        if (text.trimStart().startsWith("#EXTM3U")) {
                            val rewritten = rewritePlaylist(text, upstream.url.toString()).toByteArray(Charsets.UTF_8)
                            writeHeaders(output, code, reason(code), "application/vnd.apple.mpegurl", rewritten.size.toLong(), null)
                            output.write(rewritten); output.flush()
                        } else {
                            writeHeaders(output, code, reason(code), upstream.contentType ?: "application/octet-stream", body.size.toLong(), null)
                            output.write(body); output.flush()
                        }
                    } else {
                        val length = upstream.contentLengthLong
                        val type = upstream.contentType ?: "application/octet-stream"
                        writeHeaders(output, code, reason(code), type, length, if (length < 0) "chunked" else null, upstream.getHeaderField("Content-Range"))
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = bufferedStream.read(buffer)
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
                runCatching { respond(BufferedOutputStream(client.getOutputStream()), 502, "Bad Gateway", ByteArray(0)) }
            }
        }
    }

    private fun rewritePlaylist(body: String, baseUrl: String): String {
        fun proxy(uri: String): String {
            val raw = uri.trim()
            if (raw.isEmpty() || raw.startsWith("data:", true) || raw.startsWith("skd:", true)) return uri
            return try {
                val absolute = URI(baseUrl).resolve(raw).toString()
                if (!absolute.startsWith("http://", true) && !absolute.startsWith("https://", true)) uri
                else "http://127.0.0.1:${server.localPort}/u/" + encodeUrl(absolute)
            } catch (_: Throwable) { uri }
        }
        val uriAttr = Regex("URI=\"([^\"]+)\"")
        return body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) line
            else if (!trimmed.startsWith("#")) proxy(trimmed)
            else uriAttr.replace(line) { m -> "URI=\"${proxy(m.groupValues[1])}\"" }
        }
    }

    private fun encodeUrl(value: String): String = android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decodeUrl(value: String): String? = try { String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8) } catch (_: Throwable) { null }

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
