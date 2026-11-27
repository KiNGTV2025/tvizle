package com.umitm0d.tvizle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sayfa APK'da DÜZ HTML olarak bulunmaz. Sunucudan ŞİFRELİ blob (UTV1 biçimi) çekilir,
 * anahtar yalnızca Worker'dan (/pk) alınır, çözülen HTML sadece bellekte tutulur.
 *
 * Blob: "UTV1" | buildId(16) | iv(12) | AES-256-GCM(gzip(html)) + tag(16)
 * Çevrimdışı: şifreli blob + anahtar (Android Keystore ile sarılı) dosyada saklanır; düz HTML asla diske yazılmaz.
 */
object SecurePage {
    private const val KS_ALIAS = "utv_pk_wrap"

    fun load(ctx: Context, blobUrl: String, keyUrls: List<String>, appSecret: String, certSha: String): ByteArray? {
        val dir = ctx.filesDir
        val cacheBlob = File(dir, ".pg")
        // 1) ağdan taze blob; olmazsa önbellekteki şifreli kopya
        val bust = blobUrl + (if (blobUrl.contains('?')) "&" else "?") + "_=" + System.currentTimeMillis()   // CDN kenar önbelleğini atla: yeni sürüm hemen gelsin
        val fresh = runCatching { download(bust) }.getOrNull()
        for ((blob, isFresh) in listOf(fresh to true, runCatching { cacheBlob.readBytes() }.getOrNull() to false)) {
            if (blob == null || blob.size < 4 + 16 + 12 + 16 || String(blob, 0, 4, Charsets.US_ASCII) != "UTV1") continue
            val buildId = blob.copyOfRange(4, 20)
            val key = getKey(ctx, buildId, keyUrls, appSecret, certSha) ?: continue
            val html = runCatching { decrypt(blob, key) }.getOrNull() ?: continue
            if (isFresh) runCatching { cacheBlob.writeBytes(blob) }
            return html
        }
        return null
    }

    // ───────────── anahtar ─────────────
    private fun getKey(ctx: Context, buildId: ByteArray, keyUrls: List<String>, secret: String, cert: String): ByteArray? {
        val idHex = hex(buildId)
        val f = File(ctx.filesDir, ".pk_$idHex")
        // önbellekteki (Keystore ile sarılmış) anahtar
        runCatching { if (f.exists()) return unwrap(f.readBytes()) }
        // Worker'dan iste
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }.let(::hex)
        val sig = hex(hmac(secret.toByteArray(Charsets.UTF_8), listOf(idHex, ts, nonce, cert).joinToString(".")))
        // Birden çok Worker: ilk geçerli cevabı veren kullanılır
        var body: String? = null
        for (keyUrl in keyUrls) {
            val u = "$keyUrl?b=$idHex&t=$ts&n=$nonce&c=$cert&s=$sig"
            val r = runCatching { String(download(u), Charsets.US_ASCII).trim() }.getOrNull()
            if (r != null && Regex("^[0-9a-f]{64}$").matches(r)) { body = r; break }
        }
        if (body == null) return null
        val key = unhex(body)
        // eski build anahtarlarını temizle, yenisini sarıp sakla
        ctx.filesDir.listFiles { x -> x.name.startsWith(".pk_") }?.forEach { it.delete() }
        runCatching { f.writeBytes(wrap(key)) }
        return key
    }

    private fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        val iv = blob.copyOfRange(20, 32)
        val ct = blob.copyOfRange(32, blob.size)           // şifreli veri + 16 bayt tag (JCE tag'i sonda bekler)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val gz = c.doFinal(ct)
        return GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
    }

    // ───────────── Android Keystore ile anahtar sarma ─────────────
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return kg.generateKey()
    }
    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.iv + c.doFinal(plain)
    }
    private fun unwrap(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return c.doFinal(data.copyOfRange(12, data.size))
    }

    // ───────────── yardımcılar ─────────────
    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 20000; c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) error("http ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
    private fun hmac(key: ByteArray, msg: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg.toByteArray(Charsets.UTF_8))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
