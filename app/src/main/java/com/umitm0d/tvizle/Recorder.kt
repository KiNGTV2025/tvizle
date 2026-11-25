package com.umitm0d.tvizle

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Canlı yayın kaydı: ExoPlayer ekrana çizdiği için WebView'in captureStream'i boş kalır (1 KB kayıt).
 * Bu sınıf oynatıcının kullandığı gerçek adresi (HLS ise parçaları tek tek) doğrudan dosyaya indirir.
 */
class Recorder(private val dir: File) {
    @Volatile private var running = false
    @Volatile private var bytes = 0L
    @Volatile private var err = ""
    @Volatile private var startAt = 0L
    @Volatile private var file: File? = null
    private var thread: Thread? = null

    private companion object { const val MAX_BYTES = 4L * 1024 * 1024 * 1024 }

    fun isRunning() = running

    @Synchronized
    fun start(url: String, ua: String, ref: String): String {
        stopInternal()
        dir.mkdirs()
        val f = File(dir, "rec_" + System.currentTimeMillis() + ".ts")
        file = f; bytes = 0; err = ""; running = true; startAt = System.currentTimeMillis()
        thread = Thread { work(url, ua, ref, f) }.also { it.isDaemon = true; it.start() }
        return f.name
    }

    /** {"name":..,"size":..,"dur":..,"err":..} */
    @Synchronized
    fun stop(): String {
        val f = file
        val dur = if (startAt > 0) ((System.currentTimeMillis() - startAt) / 1000).toInt() else 0
        stopInternal()
        val size = f?.length() ?: 0L
        return "{\"name\":\"" + (f?.name ?: "") + "\",\"size\":" + size + ",\"dur\":" + dur +
            ",\"err\":\"" + err.replace("\"", "'") + "\"}"
    }

    fun stat(): String =
        "{\"run\":" + running + ",\"bytes\":" + bytes + ",\"err\":\"" + err.replace("\"", "'") + "\"}"

    private fun stopInternal() {
        running = false
        try { thread?.join(5000) } catch (_: Exception) {}
        thread = null
    }

    fun fileOf(name: String): File? {
        val n = File(name).name   // yol gezintisi (../) engelle
        val f = File(dir, n)
        return if (f.exists()) f else null
    }

    private fun open(u: String, ua: String, ref: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", if (ua.isNotBlank()) ua else "Mozilla/5.0 (Linux; Android 11)")
        if (ref.isNotBlank()) c.setRequestProperty("Referer", ref)
        return c
    }

    private fun resolve(base: String, rel: String): String =
        try { URL(URL(base), rel.trim()).toString() } catch (_: Exception) { rel.trim() }

    private fun work(url0: String, ua: String, ref: String, f: File) {
        try {
            FileOutputStream(f, true).use { out ->
                var url = url0
                val c = open(url, ua, ref)
                val code = c.responseCode
                if (code !in 200..299) { err = "HTTP $code"; return }
                val head = c.inputStream.buffered()
                head.mark(16)
                val first = ByteArray(7)
                val n = head.read(first)
                head.reset()
                val isM3u = n >= 7 && String(first, 0, 7) == "#EXTM3U"
                if (!isM3u) { copyRaw(head, out); c.disconnect(); return }
                var text = head.readBytes().toString(Charsets.UTF_8)
                c.disconnect()
                // Ana liste ise en yüksek bant genişlikli akışı seç
                if (text.contains("#EXT-X-STREAM-INF")) {
                    var best = -1L; var bestUri = ""
                    val ls = text.lines()
                    for (i in ls.indices) {
                        if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
                            val bw = Regex("BANDWIDTH=(\\d+)").find(ls[i])?.groupValues?.get(1)?.toLong() ?: 0L
                            val u = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                            if (bw > best) { best = bw; bestUri = u }
                        }
                    }
                    if (bestUri.isEmpty()) { err = "liste okunamadı"; return }
                    url = resolve(url, bestUri)
                    val c2 = open(url, ua, ref)
                    if (c2.responseCode !in 200..299) { err = "HTTP " + c2.responseCode; return }
                    text = c2.inputStream.readBytes().toString(Charsets.UTF_8)
                    c2.disconnect()
                }
                val seen = HashSet<String>()
                var mapDone = false
                var emptyPolls = 0
                while (running) {
                    if (text.contains("#EXT-X-KEY:METHOD=AES") || text.contains("#EXT-X-KEY:METHOD=SAMPLE")) {
                        err = "şifreli yayın kaydedilemez"; return
                    }
                    var target = 4
                    Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.let { target = it.groupValues[1].toInt() }
                    var fresh = 0
                    for (line in text.lines()) {
                        if (!running) break
                        if (!mapDone && line.startsWith("#EXT-X-MAP")) {
                            Regex("URI=\"([^\"]+)\"").find(line)?.let { m ->
                                try { download(resolve(url, m.groupValues[1]), ua, ref, out) } catch (_: Exception) {}
                            }
                            mapDone = true
                        }
                        if (line.isBlank() || line.startsWith("#")) continue
                        val su = resolve(url, line)
                        if (!seen.add(su)) continue
                        fresh++
                        try { download(su, ua, ref, out) } catch (e: Exception) { err = (e.message ?: "indirme hatası").take(60) }
                        if (bytes > MAX_BYTES) { err = "boyut sınırı"; return }
                    }
                    if (text.contains("#EXT-X-ENDLIST")) return
                    emptyPolls = if (fresh == 0) emptyPolls + 1 else 0
                    if (emptyPolls > 30) { err = "yayın durdu"; return }
                    val waitMs = (target * 500L).coerceIn(1000L, 4000L)
                    var w = 0L
                    while (running && w < waitMs) { Thread.sleep(200); w += 200 }
                    if (!running) break
                    try {
                        val c3 = open(url, ua, ref)
                        if (c3.responseCode in 200..299) text = c3.inputStream.readBytes().toString(Charsets.UTF_8)
                        c3.disconnect()
                    } catch (e: Exception) { err = (e.message ?: "liste hatası").take(60) }
                }
            }
        } catch (e: Exception) {
            err = (e.message ?: "kayıt hatası").take(80)
        } finally {
            running = false
        }
    }

    private fun download(u: String, ua: String, ref: String, out: FileOutputStream) {
        val c = open(u, ua, ref)
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP " + c.responseCode)
            copyRaw(c.inputStream, out)
        } finally { c.disconnect() }
    }

    private fun copyRaw(ins: java.io.InputStream, out: FileOutputStream) {
        val buf = ByteArray(64 * 1024)
        while (running) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            bytes += n
            if (bytes > MAX_BYTES) { err = "boyut sınırı"; return }
        }
    }
}
