package com.umitm0d.tvizle

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.common.TrackSelectionParameters

/**
 * TV kutularında 4K (2160p) akışta donanım dekoder bozuk/renkli görüntü üretebiliyor (telefonda sorunsuz).
 * TV/kutu cihazlarında varsayılan olarak 1080p ile sınırla ve asenkron MediaCodec kuyruğunu kapat.
 */
object DecoderTweaks {
    fun isTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = ctx.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        // Markasız/genel TV kutuları (örn. Hiremco Matrix Pro) çoğu zaman TV modu ve leanback bildirmez:
        // telefon özelliği (SIM/telefon) yok + geniş ekran ise kutu say.
        val noPhone = !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val wide = ctx.resources.configuration.smallestScreenWidthDp >= 600
        return noPhone && wide
    }

    /** Allwinner OMX H.264/HEVC dekoderi bazı kutularda yeşil/mor (kroma hatalı) görüntü üretiyor; en sona itilir. */
    fun isBrokenHw(name: String): Boolean = name.startsWith("OMX.allwinner", ignoreCase = true)

    /** Allwinner OMX'i en sona itmek ARTIK varsayılan değil (yazılım dekodere düşüp kasıyordu).
     *  Yeşil/mor görüntü olan cihazda sayfadan "sw" seçilir ya da prefs "dec"="demoteAw" yazılır. */
    @Volatile var demoteAllwinner: Boolean = false

    private fun isSw(i: MediaCodecInfo): Boolean =
        i.softwareOnly || i.name.startsWith("c2.android.") || i.name.startsWith("OMX.google.")

    /** Dekoder sırası: preferSw ise yazılım önce; değilse sağlam donanım → yazılım → bozuk donanım (Allwinner). */
    fun sortDecoders(list: List<MediaCodecInfo>, mime: String, preferSw: Boolean): List<MediaCodecInfo> {
        if (!mime.startsWith("video/")) return list
        return if (preferSw) list.sortedBy { i: MediaCodecInfo -> if (isSw(i)) 0 else 1 }
        else list.sortedBy { i: MediaCodecInfo -> if (demoteAllwinner && isBrokenHw(i.name)) 2 else if (isSw(i)) 1 else 0 }
    }

    fun selector(preferSw: () -> Boolean): MediaCodecSelector = MediaCodecSelector { mime, secure, tunneling ->
        sortDecoders(MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling), mime, preferSw())
    }

    fun isSwName(name: String): Boolean = name.startsWith("c2.android.") || name.startsWith("OMX.google.")

    fun renderers(ctx: Context): DefaultRenderersFactory =
        DefaultRenderersFactory(ctx).forceDisableMediaCodecAsynchronousQueueing()

    fun cap(ctx: Context, b: TrackSelectionParameters.Builder): TrackSelectionParameters.Builder =
        if (isTv(ctx)) b.setMaxVideoSize(Int.MAX_VALUE, 1088) else b
}
