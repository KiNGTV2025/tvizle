package com.umitm0d.tvizle

import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Harici (PlayerActivity) ve ana (NativeVideo) oynatıcının ORTAK ExoPlayer ayarları.
 * Tek yerden yönetilir; iki oynatıcı da aynı bağlantı süresi, tampon ve TS çözme ayarını kullanır.
 */
object ExoConfig {
    /** true: ana oynatıcı harici oynatıcıyla birebir aynı davranır (varsayılan yeniden deneme politikası, canlı kenar ayarı yok).
     *  false: NativeVideo'nun eski özel politikası (8 deneme, canlı kenar 10 sn) geri gelir. */
    const val MATCH_EXTERNAL = true

    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun http(ua: String, referer: String): DefaultHttpDataSource.Factory {
        val f = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)    // takılan bağlantı çabuk düşsün, yeniden deneme devreye girsin
            .setReadTimeoutMs(10000)
            .setUserAgent(if (ua.isNotBlank()) ua else DEFAULT_UA)
        if (referer.isNotBlank()) f.setDefaultRequestProperties(mapOf("Referer" to referer))
        return f
    }

    /**
     * Canlı yayında geçici parça/liste hatası (zaman aşımı, 404/410 gecikmesi, CDN dalgalanması) oynatıcıyı
     * düşürmesin: varsayılan 3 deneme yerine 6 deneme, bekleme en fazla 2 sn.
     * Ayrıştırma (ParserException) gibi kalıcı hatalar için taban sınıfın kararı korunur (sonsuz döngü yok).
     */
    fun loadErrorPolicy(): LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(6) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val ex = info.exception
            if (ex is HttpDataSource.InvalidResponseCodeException) {
                when (ex.responseCode) {
                    // parça henüz yayınlanmamış / CDN düğümü gecikmiş olabilir: biraz sabret
                    404, 410 -> return if (info.errorCount <= 4) 1000L else C.TIME_UNSET
                    // token bitti: kısa dene, sonra vazgeç (üst katman adresi yeniden çözer)
                    401, 403 -> return if (info.errorCount <= 2) 800L else C.TIME_UNSET
                }
            }
            val d = super.getRetryDelayMsFor(info)
            return if (d == C.TIME_UNSET) d else d.coerceAtMost(2000L)
        }
    }

    /** IPTV için: bozuk/kesik TS akışlarını toleranslı çöz, hızlı kanal açılışı. */
    fun extractors(): DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        .setConstantBitrateSeekingEnabled(true)

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15000, 50000, 2000, 4000)
        .build()
}
