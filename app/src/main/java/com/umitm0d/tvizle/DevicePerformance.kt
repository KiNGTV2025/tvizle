package com.umitm0d.tvizle

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Hafif profil: eski TV box'larda WebView/ExoPlayer bellek ve efekt yükünü sınırlamak için. */
object DevicePerformance {
    @Volatile private var cached: Boolean? = null

    /** Sonuç cihaz için sabittir; ActivityManager/PackageManager sorguları her çağrıda tekrarlanmasın. */
    fun isLowEnd(context: Context): Boolean {
        cached?.let { return it }
        val r = compute(context.applicationContext ?: context)
        cached = r
        return r
    }

    private fun compute(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val totalRam = if (Build.VERSION.SDK_INT >= 16) memory.totalMem else 0L
        val memoryClass = am?.memoryClass ?: 128
        val tv = DecoderTweaks.isTv(context)
        // Android'ın kendi düşük-RAM işaretini önceliklendir. Telefonlarda gereksiz yere
        // Lite profiline geçmemek için TV dışındaki eşikleri daha muhafazakâr tut.
        val platformLowRam = Build.VERSION.SDK_INT >= 19 && runCatching { am?.isLowRamDevice == true }.getOrDefault(false)
        val veryLowRam = totalRam in 1L until (1_800L * 1024L * 1024L) || memoryClass <= 96 || cores <= 2
        val constrainedTv = tv && (totalRam in 1L until (3_000L * 1024L * 1024L) || memoryClass <= 160 || cores <= 4)
        return platformLowRam || veryLowRam || constrainedTv
    }
}
