package com.umitm0d.tvizle

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.media3.exoplayer.DefaultRenderersFactory
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

    fun renderers(ctx: Context): DefaultRenderersFactory =
        DefaultRenderersFactory(ctx).forceDisableMediaCodecAsynchronousQueueing()

    fun cap(ctx: Context, b: TrackSelectionParameters.Builder): TrackSelectionParameters.Builder =
        if (isTv(ctx)) b.setMaxVideoSize(Int.MAX_VALUE, 1088) else b
}
