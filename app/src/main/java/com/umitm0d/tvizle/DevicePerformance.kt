package com.umitm0d.tvizle

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Hafif profil: eski TV box'larda WebView/ExoPlayer bellek ve efekt yükünü sınırlamak için. */
object DevicePerformance {
    fun isLowEnd(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        if (!DecoderTweaks.isTv(context)) return false
        val cores = Runtime.getRuntime().availableProcessors()
        val totalRam = if (Build.VERSION.SDK_INT >= 16) memory.totalMem else 0L
        val memoryClass = am?.memoryClass ?: 128
        return (totalRam in 1L until 3_000L * 1024L * 1024L) || memoryClass <= 160 || cores <= 4
    }
}
