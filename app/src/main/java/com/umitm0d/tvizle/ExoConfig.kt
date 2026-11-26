package com.umitm0d.tvizle

import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import android.content.Context

/**
 * Harici (PlayerActivity) ve ana (NativeVideo) oynatıcının ORTAK ExoPlayer ayarları.
 * Tek yerden yönetilir; iki oynatıcı da aynı bağlantı süresi, tampon ve TS çözme ayarını kullanır.
 */
object ExoConfig {
    /** Ana ve harici oynatıcılar için ortak HTTP, tampon ve canlı yayın hata toleransı ayarları. */

    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun http(ua: String, referer: String): DefaultHttpDataSource.Factory {
        val f = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)   // kısa ağ dalgalanmalarında bağlantıyı erken kesme
            .setReadTimeoutMs(20000)      // canlı HLS segmentlerinin geç gelmesine tolerans
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
                    404, 410 -> return if (info.errorCount <= 8) 1500L else C.TIME_UNSET
                    // token bitti: kısa dene, sonra vazgeç (üst katman adresi yeniden çözer)
                    401, 403 -> return if (info.errorCount <= 2) 1000L else C.TIME_UNSET
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

    fun loadControl(context: Context? = null): DefaultLoadControl {
        val low = context?.let { DevicePerformance.isLowEnd(it) } == true
        return DefaultLoadControl.Builder()
            // Vavoo_Stream 1.3.8 (smali'den okundu): min 15 sn, max 50 sn; yayın başlatma / kopma sonrası devam eşiği
            // "low" profilde 1 sn / 2 sn, normalde 2,5 sn / 5 sn. Eskiden 4-5 sn / 9-12 sn idi: her tampon boşalmasında
            // uzun bekleme ve logo ekranı oluyordu.
            .setBufferDurationsMs(
                15000,
                50000,
                if (low) 1000 else 2500,
                if (low) 2000 else 5000
            )
            .setTargetBufferBytes(if (low) 32 * 1024 * 1024 else 64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
}
