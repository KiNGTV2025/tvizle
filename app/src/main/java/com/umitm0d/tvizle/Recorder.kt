package com.umitm0d.tvizle

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ExoPlayer ile aynı çözümlenmiş URL/UA/Referer bilgisini kullanarak canlı HLS kaydı.
 * ExoPlayer'ın Surface/ekran çıktısını yakalamaz; HLS medya parçalarını indirip gerçek
 * taşıma akışını saklar. TS parçaları TS olarak, fMP4 HLS ise init + fragment olarak yazılır.
 */
class Recorder(private val dir: File, private val sourceProvider: (() -> Array<String>)? = null) {
    @Volatile private var running = false
    @Volatile private var bytes = 0L
    @Volatile private var err = ""
    @Volatile private var startAt = 0L
    @Volatile private var file: File? = null
    @Volatile private var mime = "video/mp2t"
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var sourceIdentity: String = ""

    private data class KeyInfo(val method: String, val uri: String?, val iv: ByteArray?)
    private data class ByteRange(val length: Long, val offset: Long)

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
        const val CONNECT_MS = 12000
        const val READ_MS = 20000
    }

    fun isRunning() = running

    @Synchronized
    fun start(url: String, ua: String, ref: String, identity: String = ""): String {
        stopInternal()
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return ""
        dir.mkdirs()
        bytes = 0L; err = ""; startAt = System.currentTimeMillis(); running = true; sourceIdentity = identity
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        mime = when {
            path.endsWith(".m3u8") || path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mp4") || path.endsWith(".m4s") -> "video/mp4"
            else -> "application/octet-stream"
        }
        val ext = when (mime) { "video/mp2t" -> "ts"; "video/mp4" -> "mp4"; else -> "media" }
        val f = File(dir, "umit_kayit_${System.currentTimeMillis()}.$ext")
        file = f
        worker = Thread({ work(url, ua, ref, f) }, "UmitTV-Recorder").also { it.isDaemon = true; it.start() }
        return f.name
    }

    @Synchronized
    fun stop(): String {
        val duration = if (startAt > 0) ((System.currentTimeMillis() - startAt) / 1000).toInt() else 0
        stopInternal()
        val f = file
        return JSONObject().put("name", f?.name ?: "").put("size", f?.length() ?: 0L)
            .put("dur", duration).put("err", err).put("mime", mime).toString()
    }

    fun stat(): String = JSONObject().put("run", running).put("bytes", bytes)
        .put("err", err).put("mime", mime).toString()

    private fun stopInternal() {
        running = false
        try { activeConnection?.disconnect() } catch (_: Exception) {}
        try { worker?.interrupt(); worker?.join(2500) } catch (_: Exception) {}
        worker = null; activeConnection = null
    }

    fun fileOf(name: String): File? {
        val safe = File(name).name
        val f = File(dir, safe)
        return if (f.isFile && f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f else null
    }

    private fun open(url: String, ua: String, ref: String, range: ByteRange? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        activeConnection = c
        c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua.ifBlank { ExoConfig.DEFAULT_UA })
        if (ref.isNotBlank()) c.setRequestProperty("Referer", ref)
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Connection", "close")
        if (range != null) c.setRequestProperty("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
        return c
    }

    private fun resolve(base: String, relative: String): String = URL(URL(base), relative.trim()).toString()

    private fun work(source: String, ua: String, ref: String, outFile: File) {
        try {
            val c = open(source, ua, ref)
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val type = c.contentType ?: ""
                val input = c.inputStream.buffered()
                input.mark(16)
                val head = ByteArray(7)
                val count = input.read(head)
                input.reset()
                val isM3u = count >= 7 && head.toString(Charsets.UTF_8) == "#EXTM3U"
                if (isM3u || source.contains(".m3u8", true) || type.contains("mpegurl", true)) {
                    val playlist = input.readBytes().toString(Charsets.UTF_8)
                    val finalUrl = c.url.toString()
                    c.disconnect(); activeConnection = null
                    recordHls(playlist, finalUrl, source, ua, ref, outFile)
                } else {
                    mime = when {
                        type.contains("video/mp4", true) || source.contains(".mp4", true) -> "video/mp4"
                        type.contains("video/mp2t", true) || source.contains(".ts", true) -> "video/mp2t"
                        else -> "application/octet-stream"
                    }
                    val ext = when (mime) { "video/mp4" -> "mp4"; "video/mp2t" -> "ts"; else -> "media" }
                    val target = File(outFile.parentFile, outFile.nameWithoutExtension + "." + ext)
                    if (target != outFile && outFile.renameTo(target)) file = target
                    FileOutputStream(file ?: outFile, false).use { out -> input.use { copyStream(it, out) } }
                    c.disconnect(); activeConnection = null
                }
            } finally {
                c.disconnect()
                if (activeConnection === c) activeConnection = null
            }
        } catch (e: Exception) {
            if (running) err = (e.message ?: "Kayıt hatası").take(120)
        } finally {
            running = false
            try { activeConnection?.disconnect() } catch (_: Exception) {}
            activeConnection = null
        }
    }

    private fun recordHls(initialText: String, initialUrl: String, rootUrl: String, initialUa: String, initialRef: String, outFile: File) {
        var rootSource = rootUrl
        var ua = initialUa
        var ref = initialRef
        var playlistUrl = initialUrl
        var text = initialText
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variants = parseVariants(text, playlistUrl)
            if (variants.isEmpty()) throw IllegalStateException("Ana HLS listesinde yayın bulunamadı")
            // Kayıt için en yüksek makul bant genişliğini seç; çok yüksek profilli kutu akışını zorlamaz.
            val chosen = variants.filter { it.second <= 8_000_000L }.maxByOrNull { it.second } ?: variants.maxBy { it.second }
            playlistUrl = chosen.first
            text = fetchText(playlistUrl, ua, ref)
        }

        val seen = LinkedHashSet<String>()
        var sequence = 0L
        var mapWritten = false
        var lastRangeEnd = 0L
        val keyCache = HashMap<String, ByteArray>()
        FileOutputStream(outFile, false).use { out ->
            while (running) {
                if (!text.trimStart().startsWith("#EXTM3U")) throw IllegalStateException("Geçersiz HLS listesi")
                val lines = text.lines()
                Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { sequence = it }
                var activeKey = KeyInfo("NONE", null, null)
                var pendingRange: ByteRange? = null
                var fresh = 0
                for (line0 in lines) {
                    if (!running) break
                    val line = line0.trim()
                    if (line.startsWith("#EXT-X-KEY:")) {
                        activeKey = parseKeyLine(line)
                        if (activeKey.method.startsWith("SAMPLE-AES")) throw IllegalStateException("SAMPLE-AES korumalı yayın kaydedilemez")
                        if (activeKey.method !in listOf("NONE", "AES-128")) throw IllegalStateException("Desteklenmeyen şifreleme: ${activeKey.method}")
                    } else if (!mapWritten && line.startsWith("#EXT-X-MAP:")) {
                        val uri = attr(line, "URI") ?: throw IllegalStateException("HLS init parçası eksik")
                        val br = parseRange(attr(line, "BYTERANGE"), 0L)
                        val data = fetchBytes(resolve(playlistUrl, uri), ua, ref, br)
                        out.write(data); out.flush(); bytes += data.size; mapWritten = true
                        mime = "video/mp4"
                        val mp4File = File(outFile.parentFile, outFile.nameWithoutExtension + ".mp4")
                        if (outFile != mp4File && outFile.renameTo(mp4File)) file = mp4File
                    } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                        pendingRange = parseRange(line.substringAfter(':'), lastRangeEnd)
                        lastRangeEnd = pendingRange?.let { it.offset + it.length } ?: lastRangeEnd
                    } else if (line.isNotEmpty() && !line.startsWith("#")) {
                        val segmentUrl = resolve(playlistUrl, line)
                        val dedupe = segmentUrl + (pendingRange?.let { "@${it.offset}:${it.length}" } ?: "")
                        if (seen.add(dedupe)) {
                            val segment = fetchBytes(segmentUrl, ua, ref, pendingRange)
                            val output = if (activeKey.method == "AES-128") {
                                val keyUrl = activeKey.uri ?: throw IllegalStateException("AES anahtarı bulunamadı")
                                val absoluteKeyUrl = resolve(playlistUrl, keyUrl)
                                val keyBytes = keyCache.getOrPut(absoluteKeyUrl) { fetchBytes(absoluteKeyUrl, ua, ref) }
                                if (keyBytes.size != 16) throw IllegalStateException("AES anahtar boyutu geçersiz")
                                decryptAes128(segment, keyBytes, activeKey.iv ?: sequenceIv(sequence))
                            } else segment
                            out.write(output); out.flush(); bytes += output.size
                            if (bytes > MAX_BYTES) throw IllegalStateException("Kayıt 8 GB sınırına ulaştı")
                            fresh++
                        }
                        pendingRange = null
                        sequence++
                    }
                }
                if (text.contains("#EXT-X-ENDLIST")) break
                if (!running) break
                if (fresh == 0) Thread.sleep(1200L) else {
                    val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 4L
                    Thread.sleep((target * 450L).coerceIn(1000L, 4000L))
                }
                if (!running) break
                // Vavoo imzalı adresi ExoPlayer yenilediyse, aynı kanal kimliği korunurken kayıt da yeni URL'ye geçer.
                val freshSource = runCatching { sourceProvider?.invoke() }.getOrNull()
                val freshUrl = freshSource?.getOrNull(0).orEmpty()
                if (sourceIdentity.isNotBlank() && freshSource?.getOrNull(3) == sourceIdentity &&
                    freshUrl.isNotBlank() && freshUrl != rootSource) {
                    rootSource = freshUrl
                    ua = freshSource.getOrElse(1) { ua }
                    ref = freshSource.getOrElse(2) { ref }
                    playlistUrl = rootSource
                    text = fetchText(playlistUrl, ua, ref)
                    if (text.contains("#EXT-X-STREAM-INF")) {
                        val variants = parseVariants(text, playlistUrl)
                        if (variants.isEmpty()) throw IllegalStateException("Yenilenen HLS listesi boş")
                        playlistUrl = (variants.filter { it.second <= 8_000_000L }.maxByOrNull { it.second } ?: variants.maxBy { it.second }).first
                        text = fetchText(playlistUrl, ua, ref)
                    }
                } else {
                    text = fetchText(playlistUrl, ua, ref)
                }
                if (seen.size > 3000) {
                    val keep = seen.toList().takeLast(1500)
                    seen.clear(); seen.addAll(keep)
                }
            }
        }
    }

    private fun parseVariants(text: String, base: String): List<Pair<String, Long>> {
        val lines = text.lines(); val out = ArrayList<Pair<String, Long>>()
        for (i in lines.indices) if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
            val bw = Regex("(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim() ?: continue
            out += resolve(base, uri) to bw
        }
        return out
    }

    private fun parseKeyLine(line: String): KeyInfo {
        val method = attr(line, "METHOD") ?: "NONE"
        val uri = attr(line, "URI")
        val ivText = attr(line, "IV")
        return KeyInfo(method, uri, ivText?.let { parseIv(it) })
    }

    private fun attr(line: String, name: String): String? = Regex("(?:^|[, :])${Regex.escape(name)}=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
        ?: Regex("(?:^|[, :])${Regex.escape(name)}=([^,]*)").find(line)?.groupValues?.get(1)?.trim()

    private fun parseRange(raw: String?, fallback: Long): ByteRange? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('@', limit = 2)
        val length = parts[0].toLongOrNull() ?: return null
        val offset = if (parts.size > 1) parts[1].toLongOrNull() ?: fallback else fallback
        return if (length > 0) ByteRange(length, offset) else null
    }

    private fun parseIv(raw: String): ByteArray {
        val hex = raw.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun sequenceIv(seq: Long): ByteArray = ByteArray(16).also { b ->
        var v = seq
        for (i in 15 downTo 8) { b[i] = (v and 0xff).toByte(); v = v ushr 8 }
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun fetchText(url: String, ua: String, ref: String): String = fetchBytes(url, ua, ref).toString(Charsets.UTF_8)

    private fun fetchBytes(url: String, ua: String, ref: String, range: ByteRange? = null): ByteArray {
        val c = open(url, ua, ref, range)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.use { input ->
                if (range == null || code == 206) input.readBytes()
                else {
                    var left = range.offset
                    while (left > 0) { val skipped = input.skip(left); if (skipped <= 0) { if (input.read() < 0) break else left-- } else left -= skipped }
                    val out = java.io.ByteArrayOutputStream(range.length.coerceAtMost(32L * 1024 * 1024).toInt())
                    val buf = ByteArray(16 * 1024); var remain = range.length
                    while (remain > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt()); if (n < 0) break; out.write(buf, 0, n); remain -= n }
                    out.toByteArray()
                }
            }
        } finally { c.disconnect(); if (activeConnection === c) activeConnection = null }
    }

    private fun copyStream(input: java.io.InputStream, out: FileOutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (running) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n); bytes += n
            if (bytes > MAX_BYTES) throw IllegalStateException("Kayıt 8 GB sınırına ulaştı")
        }
    }
}
